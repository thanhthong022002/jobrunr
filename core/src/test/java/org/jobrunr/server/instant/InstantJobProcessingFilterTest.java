package org.jobrunr.server.instant;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.states.EnqueuedState;
import org.jobrunr.jobs.states.ProcessingState;
import org.jobrunr.jobs.states.ScheduledState;
import org.jobrunr.server.BackgroundJobServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jobrunr.jobs.JobTestBuilder.aScheduledJob;
import static org.jobrunr.jobs.JobTestBuilder.anEnqueuedJob;
import static org.jobrunr.jobs.RecurringJobTestBuilder.aDefaultRecurringJob;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InstantJobProcessingFilterTest {

    @Mock
    private BackgroundJobServer backgroundJobServer;

    private CountingJobEnqueuedPublisher publisher;
    private InstantJobProcessingFilter filter;

    @BeforeEach
    void setUp() {
        publisher = new CountingJobEnqueuedPublisher();
        filter = new InstantJobProcessingFilter(backgroundJobServer, publisher);
    }

    @AfterEach
    void tearDown() {
        filter.close();
    }

    @Test
    void onCreatedOfAnEnqueuedJobOnboardsNewWorkImmediately() {
        filter.onCreated(anEnqueuedJob().build());

        verify(backgroundJobServer, timeout(5000)).onboardNewWorkNow();
    }

    @Test
    void onCreatedOfAScheduledJobDoesNotOnboardNewWork() {
        filter.onCreated(aScheduledJob().build());

        assertNothingIsOnboarded();
    }

    @Test
    void onCreatedOfARecurringJobDoesNotOnboardNewWork() {
        RecurringJob recurringJob = aDefaultRecurringJob().build();

        filter.onCreated(recurringJob);

        assertNothingIsOnboarded();
    }

    @Test
    void onStateAppliedToEnqueuedOnboardsNewWorkImmediately() {
        // this is the SCHEDULED -> ENQUEUED transition done by ProcessScheduledJobsTask on the master node - a
        // JobClientFilter alone would never see it
        Job job = aScheduledJob().build();

        filter.onStateApplied(job, new ScheduledState(now()), new EnqueuedState());

        verify(backgroundJobServer, timeout(5000)).onboardNewWorkNow();
    }

    @Test
    void onStateAppliedToAnyOtherStateDoesNotOnboardNewWork() {
        Job job = anEnqueuedJob().build();

        filter.onStateApplied(job, new EnqueuedState(), new ProcessingState(UUID.randomUUID(), "a server"));

        assertNothingIsOnboarded();
    }

    @Test
    void aBurstOfEnqueuedJobsIsCoalescedIntoFarFewerOnboardingPasses() {
        AtomicInteger onboardingPasses = new AtomicInteger();
        doAnswer(invocation -> {
            onboardingPasses.incrementAndGet();
            Thread.sleep(20); // a job-election round-trip is not free
            return null;
        }).when(backgroundJobServer).onboardNewWorkNow();

        for (int i = 0; i < 1000; i++) {
            filter.onCreated(anEnqueuedJob().build());
        }

        await().atMost(20, SECONDS).until(() -> !filter.isOnboardingPending());

        // at least one pass must have happened, but nowhere near 1000 - otherwise we would have traded one poll per 15s
        // for 1000 job-election round-trips
        assertThat(onboardingPasses.get()).isPositive().isLessThan(100);
        assertThat(publisher.publishCount()).isPositive().isLessThan(100);
    }

    @Test
    void aLocallyEnqueuedJobIsAnnouncedToTheCluster() {
        filter.onCreated(anEnqueuedJob().build());

        await().atMost(5, SECONDS).until(() -> publisher.publishCount() > 0);
    }

    @Test
    void aRemotelyEnqueuedJobOnboardsNewWorkButIsNotReAnnounced() {
        filter.onRemoteJobEnqueued();

        verify(backgroundJobServer, timeout(5000)).onboardNewWorkNow();
        // re-announcing what the cluster just told us would turn the cluster into a notification loop
        assertThat(publisher.publishCount()).isZero();
    }

    @Test
    void aFailingPublisherStillOnboardsNewWorkLocally() {
        InstantJobProcessingFilter filterUnderTest = new InstantJobProcessingFilter(backgroundJobServer, () -> {
            throw new IllegalStateException("the database is gone");
        });
        try {
            filterUnderTest.onCreated(anEnqueuedJob().build());

            verify(backgroundJobServer, timeout(5000)).onboardNewWorkNow();
        } finally {
            filterUnderTest.close();
        }
    }

    @Test
    void aFailingOnboardingIsSwallowedSoEnqueueingNeverBreaks() {
        doThrow(new IllegalStateException("storage is down")).when(backgroundJobServer).onboardNewWorkNow();

        filter.onCreated(anEnqueuedJob().build());

        verify(backgroundJobServer, timeout(5000)).onboardNewWorkNow();
        // and the filter is still usable afterwards
        await().atMost(5, SECONDS).until(() -> !filter.isOnboardingPending());
    }

    @Test
    void onceClosedNoFurtherOnboardingIsRequested() {
        filter.close();

        filter.onCreated(anEnqueuedJob().build());

        verify(backgroundJobServer, never()).onboardNewWorkNow();
    }

    private void assertNothingIsOnboarded() {
        await().during(300, MILLISECONDS)
                .atMost(2, SECONDS)
                .until(() -> !filter.isOnboardingPending());
        verify(backgroundJobServer, never()).onboardNewWorkNow();
    }

    private static class CountingJobEnqueuedPublisher implements JobEnqueuedPublisher {
        private final AtomicInteger publishCount = new AtomicInteger();

        @Override
        public void publishJobEnqueued() {
            publishCount.incrementAndGet();
        }

        int publishCount() {
            return publishCount.get();
        }
    }
}
