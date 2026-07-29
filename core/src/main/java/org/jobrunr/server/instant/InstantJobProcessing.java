package org.jobrunr.server.instant;

import org.jobrunr.server.BackgroundJobServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.singletonList;

/**
 * One-call wiring for instant (push-based) job processing.
 * <p>
 * JobRunr picks up enqueued jobs by polling every {@code pollInterval} (15s by default, 5s minimum), so a freshly
 * enqueued job waits up to that long before a worker touches it. This class removes that wait by having the enqueue path
 * itself wake the {@link BackgroundJobServer}, leaving the poll in place purely as a safety net.
 *
 * <h2>Single node</h2>
 * <pre>{@code
 * InstantJobProcessing instantProcessing = InstantJobProcessing.enableOn(backgroundJobServer);
 * JobScheduler scheduler = new JobScheduler(storageProvider, singletonList(instantProcessing.jobFilter()));
 * // ... on shutdown:
 * instantProcessing.close();
 * }</pre>
 *
 * <h2>Cluster</h2>
 * Every node needs to hear about jobs enqueued on the other nodes, which is what the
 * {@link JobEnqueuedPublisher}/{@link JobEnqueuedSubscriber} pair is for:
 * <pre>{@code
 * PostgresJobEnqueuedBridge bridge = new PostgresJobEnqueuedBridge(dataSource);
 * InstantJobProcessing instantProcessing = InstantJobProcessing.enableOn(backgroundJobServer, bridge, bridge);
 * JobScheduler scheduler = new JobScheduler(storageProvider, singletonList(instantProcessing.jobFilter()));
 * }</pre>
 *
 * <h2>Why the filter must also go on the JobScheduler</h2>
 * {@code JobScheduler} takes its filters in the constructor and never exposes them, so it cannot be retrofitted -
 * {@link #jobFilter()} exists to be passed in there. Registering on the server alone would only catch
 * {@code SCHEDULED -> ENQUEUED} transitions, not direct {@code enqueue(...)} calls.
 */
public class InstantJobProcessing implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstantJobProcessing.class);

    private final InstantJobProcessingFilter jobFilter;
    private final JobEnqueuedSubscriber jobEnqueuedSubscriber;

    private InstantJobProcessing(InstantJobProcessingFilter jobFilter, JobEnqueuedSubscriber jobEnqueuedSubscriber) {
        this.jobFilter = jobFilter;
        this.jobEnqueuedSubscriber = jobEnqueuedSubscriber;
    }

    /**
     * Enables instant processing for a single-node setup: jobs enqueued in this JVM start processing immediately.
     *
     * @param backgroundJobServer the server to wake up on enqueue
     * @return the handle; pass {@link #jobFilter()} to your {@code JobScheduler} and {@link #close()} it on shutdown
     */
    public static InstantJobProcessing enableOn(BackgroundJobServer backgroundJobServer) {
        return enableOn(backgroundJobServer, JobEnqueuedPublisher.noop(), null);
    }

    /**
     * Enables instant processing across a cluster: jobs enqueued on any node start processing on every node immediately.
     *
     * @param backgroundJobServer  the server to wake up on enqueue
     * @param jobEnqueuedPublisher announces locally enqueued jobs to the other nodes
     * @param jobEnqueuedSubscriber listens for the other nodes' announcements; may be {@code null} for a single node
     * @return the handle; pass {@link #jobFilter()} to your {@code JobScheduler} and {@link #close()} it on shutdown
     */
    public static InstantJobProcessing enableOn(BackgroundJobServer backgroundJobServer, JobEnqueuedPublisher jobEnqueuedPublisher, JobEnqueuedSubscriber jobEnqueuedSubscriber) {
        InstantJobProcessingFilter jobFilter = new InstantJobProcessingFilter(backgroundJobServer, jobEnqueuedPublisher);
        backgroundJobServer.setJobFilters(singletonList(jobFilter));
        if (jobEnqueuedSubscriber != null) {
            jobEnqueuedSubscriber.subscribe(jobFilter::onRemoteJobEnqueued);
        }
        LOGGER.info("Instant job processing enabled - enqueued jobs no longer wait for the pollInterval ({}).",
                jobEnqueuedSubscriber != null ? "cluster-wide" : "this JVM only");
        return new InstantJobProcessing(jobFilter, jobEnqueuedSubscriber);
    }

    /**
     * @return the filter to pass to the {@code JobScheduler} / {@code JobRequestScheduler} constructor so that direct
     * {@code enqueue(...)} calls are picked up instantly too. It is already registered on the BackgroundJobServer.
     */
    public InstantJobProcessingFilter jobFilter() {
        return jobFilter;
    }

    @Override
    public void close() {
        if (jobEnqueuedSubscriber != null) {
            try {
                jobEnqueuedSubscriber.close();
            } catch (Exception e) {
                LOGGER.warn("Could not close the JobEnqueuedSubscriber.", e);
            }
        }
        jobFilter.close();
    }
}
