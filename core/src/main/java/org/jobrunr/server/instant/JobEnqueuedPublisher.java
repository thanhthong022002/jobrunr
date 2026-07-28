package org.jobrunr.server.instant;

/**
 * Announces to the rest of the cluster that a job has just been enqueued, so that other
 * {@link org.jobrunr.server.BackgroundJobServer BackgroundJobServers} can start processing it without waiting for their
 * next {@code pollInterval} tick.
 * <p>
 * Only needed when more than one BackgroundJobServer is running. A single-node setup can use {@link #noop()}: the
 * enqueueing JVM <em>is</em> the processing JVM, so a local wake-up is enough.
 * <p>
 * Implementations are called from a single background thread and may block (a database round-trip is fine). They must
 * not throw - {@link InstantJobProcessingFilter} logs and swallows failures because a missed notification only costs
 * latency: the regular poll still picks the job up.
 *
 * @see org.jobrunr.server.instant.postgres.PostgresJobEnqueuedBridge
 */
public interface JobEnqueuedPublisher extends AutoCloseable {

    /**
     * Tells the cluster that at least one job was enqueued since the last announcement. Announcements are coalesced by
     * the caller, so this is invoked once per burst rather than once per job - implementations do not need to
     * rate-limit.
     */
    void publishJobEnqueued();

    @Override
    default void close() {
        // nothing to release by default
    }

    /**
     * @return a publisher that does nothing - the correct choice for a single-node setup.
     */
    static JobEnqueuedPublisher noop() {
        return () -> {
            // single node: the local wake-up already covers it
        };
    }
}
