package org.jobrunr.server.instant;

/**
 * Listens for the announcements made by a {@link JobEnqueuedPublisher} on other nodes and wakes up the local
 * {@link org.jobrunr.server.BackgroundJobServer} when one arrives.
 * <p>
 * Only needed when more than one BackgroundJobServer is running.
 * <p>
 * Implementations own their own thread (they must not block {@link #subscribe(Runnable)}) and are expected to survive a
 * dropped connection by reconnecting - if a subscriber dies silently, the node degrades to plain polling, which is
 * correct but slow.
 *
 * @see org.jobrunr.server.instant.postgres.PostgresJobEnqueuedBridge
 */
public interface JobEnqueuedSubscriber extends AutoCloseable {

    /**
     * Starts listening. Must return immediately; {@code onJobEnqueued} is invoked (possibly many times) on a thread
     * owned by the implementation for every announcement received.
     *
     * @param onJobEnqueued the callback to run when another node announces an enqueued job. Never throws.
     */
    void subscribe(Runnable onJobEnqueued);

    @Override
    default void close() {
        // nothing to release by default
    }
}
