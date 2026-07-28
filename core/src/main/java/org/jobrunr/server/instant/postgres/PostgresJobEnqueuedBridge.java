package org.jobrunr.server.instant.postgres;

import org.jobrunr.server.instant.JobEnqueuedPublisher;
import org.jobrunr.server.instant.JobEnqueuedSubscriber;
import org.jobrunr.storage.StorageException;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Cluster-wide instant job processing on PostgreSQL, using {@code LISTEN}/{@code NOTIFY} - no broker, no extra
 * infrastructure, just the database JobRunr already talks to.
 * <p>
 * Acts as both ends of the wire: {@link JobEnqueuedPublisher} does a {@code pg_notify(...)} when a job is enqueued
 * locally, and {@link JobEnqueuedSubscriber} holds a dedicated connection that blocks on {@code getNotifications(...)}
 * and wakes the local server when another node fires. Register the same instance as both:
 * <pre>{@code
 * PostgresJobEnqueuedBridge bridge = new PostgresJobEnqueuedBridge(dataSource);
 * InstantJobProcessing.enableOn(backgroundJobServer, bridge, bridge);
 * }</pre>
 *
 * <h2>Notes</h2>
 * <ul>
 *     <li><b>One connection per node is held for the lifetime of the subscriber.</b> Size your pool accordingly, or hand
 *     in a separate {@link DataSource} for it - it is idle-blocked almost all of the time, so a pool that hands out its
 *     last connection to this listener would starve.</li>
 *     <li><b>Notifications are only delivered on commit.</b> That is exactly what we want: by the time other nodes wake
 *     up, the job row is visible to them.</li>
 *     <li>A dropped connection is not fatal - the listener reconnects, and until it does the node simply falls back to
 *     its normal {@code pollInterval}.</li>
 *     <li><b>Alternative:</b> a row-level trigger (<code>AFTER INSERT ON jobrunr_jobs ... EXECUTE pg_notify</code>) would
 *     also catch jobs enqueued by anything that is not this application, at the cost of DDL plus one notification per
 *     row. The application-side publisher used here needs no DDL and is coalesced per burst by
 *     {@link org.jobrunr.server.instant.InstantJobProcessingFilter}.</li>
 * </ul>
 */
public class PostgresJobEnqueuedBridge implements JobEnqueuedPublisher, JobEnqueuedSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresJobEnqueuedBridge.class);

    public static final String DEFAULT_CHANNEL = "jobrunr_job_enqueued";
    private static final Duration DEFAULT_NOTIFICATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RECONNECT_DELAY = Duration.ofSeconds(5);
    /** A LISTEN channel is an identifier and cannot be a bind parameter, so it is validated instead of escaped. */
    private static final Pattern VALID_CHANNEL = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String THREAD_NAME = "jobrunr-pg-job-enqueued-listener";

    private final DataSource dataSource;
    private final String channel;
    private final Duration notificationTimeout;
    private final Duration reconnectDelay;

    private volatile boolean stopped;
    private volatile Connection listenerConnection;
    private Thread listenerThread;

    public PostgresJobEnqueuedBridge(DataSource dataSource) {
        this(dataSource, DEFAULT_CHANNEL);
    }

    public PostgresJobEnqueuedBridge(DataSource dataSource, String channel) {
        this(dataSource, channel, DEFAULT_NOTIFICATION_TIMEOUT, DEFAULT_RECONNECT_DELAY);
    }

    public PostgresJobEnqueuedBridge(DataSource dataSource, String channel, Duration notificationTimeout, Duration reconnectDelay) {
        if (dataSource == null) throw new IllegalArgumentException("A DataSource is required");
        if (channel == null || !VALID_CHANNEL.matcher(channel).matches()) {
            throw new IllegalArgumentException("The channel must be a lowercase unquoted Postgres identifier (matching " + VALID_CHANNEL.pattern() + ") but was: " + channel);
        }
        if (notificationTimeout == null || notificationTimeout.isNegative() || notificationTimeout.isZero()) {
            throw new IllegalArgumentException("The notificationTimeout must be positive (a zero timeout blocks forever and would prevent a clean shutdown)");
        }
        this.dataSource = dataSource;
        this.channel = channel;
        this.notificationTimeout = notificationTimeout;
        this.reconnectDelay = reconnectDelay;
    }

    @Override
    public void publishJobEnqueued() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT pg_notify(?, '')")) {
            statement.setString(1, channel);
            statement.execute();
        } catch (SQLException e) {
            throw new StorageException("Could not notify the cluster on channel " + channel, e);
        }
    }

    @Override
    public synchronized void subscribe(Runnable onJobEnqueued) {
        if (onJobEnqueued == null) throw new IllegalArgumentException("A callback is required");
        if (listenerThread != null) throw new IllegalStateException("Already subscribed - create a separate bridge per subscription");
        stopped = false;
        listenerThread = new Thread(() -> listen(onJobEnqueued), THREAD_NAME);
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listen(Runnable onJobEnqueued) {
        while (!stopped) {
            try (Connection connection = dataSource.getConnection()) {
                listenerConnection = connection;
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + channel);
                }
                LOGGER.debug("Listening for enqueued jobs on Postgres channel {}.", channel);
                PGConnection pgConnection = connection.unwrap(PGConnection.class);
                while (!stopped) {
                    PGNotification[] notifications = pgConnection.getNotifications((int) notificationTimeout.toMillis());
                    if (stopped) return;
                    // null means "timed out, nothing arrived" - just loop so we keep checking `stopped`.
                    if (notifications != null && notifications.length > 0) {
                        runQuietly(onJobEnqueued);
                    }
                }
            } catch (Exception e) {
                if (stopped) return;
                LOGGER.warn("Lost the Postgres notification listener on channel {}; falling back to the pollInterval and reconnecting in {}.", channel, reconnectDelay, e);
                if (!sleepBeforeReconnect()) return;
            } finally {
                listenerConnection = null;
            }
        }
    }

    private void runQuietly(Runnable onJobEnqueued) {
        try {
            onJobEnqueued.run();
        } catch (Exception e) {
            LOGGER.warn("The job-enqueued callback failed; the pollInterval will pick the job up.", e);
        }
    }

    private boolean sleepBeforeReconnect() {
        try {
            Thread.sleep(reconnectDelay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public synchronized void close() {
        stopped = true;
        // getNotifications() is blocked on a socket read, so interrupting is not enough - close the connection under it.
        Connection connection = listenerConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.debug("Could not close the notification listener connection.", e);
            }
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
    }
}
