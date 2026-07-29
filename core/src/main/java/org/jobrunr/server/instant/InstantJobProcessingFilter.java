package org.jobrunr.server.instant;

import org.jobrunr.jobs.AbstractJob;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.filters.ApplyStateFilter;
import org.jobrunr.jobs.filters.JobClientFilter;
import org.jobrunr.jobs.states.EnqueuedState;
import org.jobrunr.jobs.states.JobState;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.utils.annotations.VisibleFor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jobrunr.jobs.states.StateName.ENQUEUED;

/**
 * Makes a {@link BackgroundJobServer} start processing jobs the moment they are enqueued, instead of on the next
 * {@code pollInterval} tick. Enqueue latency drops from "up to pollInterval" (15s by default, 5s minimum) to
 * sub-millisecond, without lowering the poll interval and without the extra database load that would cause.
 *
 * <h2>Both enqueue paths are covered</h2>
 * A job reaches {@code ENQUEUED} in two different places, so this filter implements two interfaces:
 * <ul>
 *     <li>{@link JobClientFilter#onCreated(AbstractJob)} - somebody called {@code enqueue(...)}. Runs in the JVM that
 *     enqueued, which is why the filter must also be registered on the {@code JobScheduler}.</li>
 *     <li>{@link ApplyStateFilter#onStateApplied(Job, JobState, JobState)} - a {@code SCHEDULED} (or carbon-aware) job
 *     came due and was enqueued by
 *     {@link org.jobrunr.server.tasks.zookeeper.ProcessScheduledJobsTask} on the master node. A client-side-only filter
 *     would miss every scheduled job.</li>
 * </ul>
 *
 * <h2>Wiring</h2>
 * Register on <em>both</em> the server and the scheduler - see {@link InstantJobProcessing} for a one-liner that does
 * this (and the cluster wiring) for you:
 * <pre>{@code
 * InstantJobProcessingFilter filter = new InstantJobProcessingFilter(backgroundJobServer);
 * backgroundJobServer.setJobFilters(List.of(filter));          // scheduled -> enqueued
 * JobScheduler scheduler = new JobScheduler(storageProvider, List.of(filter));  // client enqueue
 * }</pre>
 * In a cluster, pass a {@link JobEnqueuedPublisher} as well and have every node run a {@link JobEnqueuedSubscriber}
 * that calls {@link #onRemoteJobEnqueued()}.
 *
 * <h2>Why it does not hammer the database</h2>
 * Wake-ups are asynchronous and coalesced. Enqueueing 10 000 jobs in a loop produces a handful of onboarding passes, not
 * 10 000, and the enqueueing thread never pays for a job-election round-trip. Losing a wake-up is harmless by design -
 * the normal poll remains the safety net - so every failure here is logged and swallowed.
 */
public class InstantJobProcessingFilter implements JobClientFilter, ApplyStateFilter, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstantJobProcessingFilter.class);
    private static final String THREAD_NAME = "jobrunr-instant-onboarding";

    private final BackgroundJobServer backgroundJobServer;
    private final JobEnqueuedPublisher jobEnqueuedPublisher;
    private final ExecutorService onboardingExecutor;
    private final AtomicBoolean onboardingRequested = new AtomicBoolean();
    private final AtomicBoolean publishRequested = new AtomicBoolean();
    private volatile boolean closed;

    /**
     * Single-node setup: only the local server is woken up.
     */
    public InstantJobProcessingFilter(BackgroundJobServer backgroundJobServer) {
        this(backgroundJobServer, JobEnqueuedPublisher.noop());
    }

    /**
     * Clustered setup: the local server is woken up and the other nodes are notified through {@code jobEnqueuedPublisher}.
     */
    public InstantJobProcessingFilter(BackgroundJobServer backgroundJobServer, JobEnqueuedPublisher jobEnqueuedPublisher) {
        if (backgroundJobServer == null) throw new IllegalArgumentException("A BackgroundJobServer is required");
        if (jobEnqueuedPublisher == null) throw new IllegalArgumentException("A JobEnqueuedPublisher is required - use JobEnqueuedPublisher.noop() for a single node");
        this.backgroundJobServer = backgroundJobServer;
        this.jobEnqueuedPublisher = jobEnqueuedPublisher;
        this.onboardingExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void onCreated(AbstractJob job) {
        // RecurringJobs have no state - only actual Jobs that went straight to ENQUEUED are interesting here.
        if (job instanceof Job && ((Job) job).hasState(ENQUEUED)) {
            onLocalJobEnqueued();
        }
    }

    @Override
    public void onStateApplied(Job job, JobState oldState, JobState newState) {
        if (newState instanceof EnqueuedState) {
            onLocalJobEnqueued();
        }
    }

    /**
     * To be called by a {@link JobEnqueuedSubscriber} when <em>another</em> node announced an enqueued job: wakes up the
     * local server but deliberately does not re-announce, which would turn the cluster into a notification loop.
     */
    public void onRemoteJobEnqueued() {
        requestOnboarding(false);
    }

    private void onLocalJobEnqueued() {
        requestOnboarding(true);
    }

    private void requestOnboarding(boolean alsoNotifyCluster) {
        if (closed) return;
        if (alsoNotifyCluster) publishRequested.set(true);
        // Already scheduled? Then that pending pass will also cover this job: it is saved to the StorageProvider before
        // this filter runs, and the pass resets the flag before querying - so it can never be missed.
        if (!onboardingRequested.compareAndSet(false, true)) return;
        try {
            onboardingExecutor.execute(this::onboardNewWork);
        } catch (RejectedExecutionException e) {
            onboardingRequested.set(false);
            LOGGER.debug("Instant onboarding was rejected (shutting down?); the pollInterval will pick the job up.", e);
        }
    }

    private void onboardNewWork() {
        onboardingRequested.set(false);
        if (publishRequested.getAndSet(false)) {
            try {
                jobEnqueuedPublisher.publishJobEnqueued();
            } catch (Exception e) {
                LOGGER.warn("Could not announce the enqueued job to the cluster; other nodes will pick it up on their pollInterval.", e);
            }
        }
        try {
            backgroundJobServer.onboardNewWorkNow();
        } catch (Exception e) {
            LOGGER.warn("Could not onboard new work instantly; the pollInterval will pick the job up.", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        onboardingExecutor.shutdown();
        try {
            if (!onboardingExecutor.awaitTermination(5, TimeUnit.SECONDS)) onboardingExecutor.shutdownNow();
        } catch (InterruptedException e) {
            onboardingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            jobEnqueuedPublisher.close();
        } catch (Exception e) {
            LOGGER.warn("Could not close the JobEnqueuedPublisher.", e);
        }
    }

    @VisibleFor("testing")
    boolean isOnboardingPending() {
        return onboardingRequested.get();
    }
}
