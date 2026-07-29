package org.jobrunr.server.instant;

import org.jobrunr.jobs.JobId;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.jobs.stubs.SimpleJobActivator;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.stubs.TestService;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jobrunr.jobs.states.StateName.ENQUEUED;
import static org.jobrunr.jobs.states.StateName.SUCCEEDED;
import static org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration;
import static org.jobrunr.utils.SleepUtils.sleep;

/**
 * Proves the point of the whole feature: with a deliberately long pollInterval, a job enqueued through a scheduler that
 * has the {@link InstantJobProcessingFilter} runs almost immediately, while the same job without the filter sits in
 * ENQUEUED until the next poll.
 */
class InstantJobProcessingTest {

    /** Long enough that "it just happened to poll" cannot explain a pass. */
    private static final Duration LONG_POLL_INTERVAL = Duration.ofSeconds(30);
    /** Comfortably past the JobSteward's fixed 1s initial delay - see {@link #startServerAndAwaitTheFirstOnboardingPass()}. */
    private static final Duration FIRST_ONBOARDING_PASS_MARGIN = Duration.ofSeconds(3);
    /** Well inside LONG_POLL_INTERVAL, so a pass within this window cannot be the poll. */
    private static final Duration ASSERTION_WINDOW = Duration.ofSeconds(5);

    private StorageProvider storageProvider;
    private BackgroundJobServer backgroundJobServer;
    private InstantJobProcessing instantJobProcessing;
    private TestService testService;

    @BeforeEach
    void setUp() {
        testService = new TestService();
        testService.reset();
        storageProvider = new InMemoryStorageProvider();
        storageProvider.setJobMapper(new JobMapper(new JacksonJsonMapper()));
        backgroundJobServer = new BackgroundJobServer(storageProvider, new JacksonJsonMapper(), new SimpleJobActivator(testService),
                usingStandardBackgroundJobServerConfiguration().andPollInterval(LONG_POLL_INTERVAL));
    }

    @AfterEach
    void tearDown() {
        if (instantJobProcessing != null) instantJobProcessing.close();
        backgroundJobServer.stop();
        storageProvider.close();
    }

    @Test
    void anEnqueuedJobIsProcessedWithoutWaitingForThePollInterval() {
        instantJobProcessing = InstantJobProcessing.enableOn(backgroundJobServer);
        JobScheduler jobScheduler = new JobScheduler(storageProvider, singletonList(instantJobProcessing.jobFilter()));
        startServerAndAwaitTheFirstOnboardingPass();

        JobId jobId = jobScheduler.enqueue(() -> testService.doWork());

        // the next scheduled pass is ~30s away, so anything this fast can only be the push
        await().atMost(ASSERTION_WINDOW)
                .untilAsserted(() -> assertThat(storageProvider.getJobById(jobId).getState()).isEqualTo(SUCCEEDED));
    }

    @Test
    void withoutTheFilterAnEnqueuedJobWaitsForThePollInterval() {
        // the control: same server, same storage, no instant processing
        JobScheduler jobScheduler = new JobScheduler(storageProvider, emptyList());
        startServerAndAwaitTheFirstOnboardingPass();

        JobId jobId = jobScheduler.enqueue(() -> testService.doWork());

        await().during(ASSERTION_WINDOW)
                .atMost(ASSERTION_WINDOW.plusSeconds(5))
                .until(() -> ENQUEUED.equals(storageProvider.getJobById(jobId).getState()));
        assertThat(testService.getProcessedJobs()).isZero();
    }

    @Test
    void aRemoteAnnouncementAlsoTriggersProcessingWithoutWaitingForThePollInterval() {
        // simulates the clustered case: the job was enqueued on ANOTHER node (so no local filter callback fires), and all
        // this node receives is the announcement its JobEnqueuedSubscriber would deliver.
        instantJobProcessing = InstantJobProcessing.enableOn(backgroundJobServer);
        JobScheduler jobSchedulerOfTheOtherNode = new JobScheduler(storageProvider, emptyList());
        startServerAndAwaitTheFirstOnboardingPass();

        JobId jobId = jobSchedulerOfTheOtherNode.enqueue(() -> testService.doWork());
        instantJobProcessing.jobFilter().onRemoteJobEnqueued();

        await().atMost(ASSERTION_WINDOW)
                .untilAsserted(() -> assertThat(storageProvider.getJobById(jobId).getState()).isEqualTo(SUCCEEDED));
    }

    /**
     * The JobSteward is scheduled with an initial delay of {@code min(pollInterval / 5, 1s)}, so its very first
     * onboarding pass happens ~1s after start no matter how long the pollInterval is. Every test here must enqueue
     * <em>after</em> that pass, otherwise a job picked up by it would look exactly like instant processing and the tests
     * would prove nothing.
     */
    private void startServerAndAwaitTheFirstOnboardingPass() {
        backgroundJobServer.start();
        await().atMost(10, SECONDS).until(() -> !backgroundJobServer.isNotReadyToProcessJobs());
        sleep(FIRST_ONBOARDING_PASS_MARGIN.toMillis());
    }
}
