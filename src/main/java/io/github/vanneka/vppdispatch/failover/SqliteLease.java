package io.github.vanneka.vppdispatch.failover;

import io.github.vanneka.vppdispatch.storage.StoreException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * A lease stored as one row in the shared SQLite file: who holds it and when it expires.
 *
 * <p>The server holding the lease renews it by moving its expiry time later. Another
 * server can take the lease only after it expires. Each check-and-update is one SQL
 * statement, so two servers cannot both win. Time comes from an injected {@link Clock}
 * so tests don't have to wait.
 *
 * <p>Known limitation (fixed in v0.3.0): a primary that is paused, not crashed, can wake
 * after its lease expired and send a command before it checks again.
 */
public final class SqliteLease implements LeaderElection, AutoCloseable {

    /** How long a lease lasts if it is not renewed. */
    public static final Duration LEASE_DURATION = Duration.ofSeconds(10);

    /** How often the primary is expected to renew its lease. */
    public static final Duration RENEW_EVERY = Duration.ofSeconds(3);

    private static final String LEASE_NAME = "dispatch";

    private final Connection connection;
    private final String instanceId;
    private final Clock clock;

    public SqliteLease(Path databaseFile, String instanceId, Clock clock) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                // Wait for the other server's write to finish instead of failing at once.
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS lease (
                            name          TEXT PRIMARY KEY,
                            holder        TEXT NOT NULL,
                            expires_at_ms INTEGER NOT NULL
                        )""");
            }
        } catch (SQLException e) {
            throw new StoreException("could not open lease in " + databaseFile, e);
        }
    }

    @Override
    public boolean tryAcquireOrRenew() {
        long now = clock.millis();
        long expiresAt = now + LEASE_DURATION.toMillis();
        try {
            // First time anyone asks: create the lease with this server as holder.
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO lease (name, holder, expires_at_ms) VALUES (?, ?, ?)")) {
                insert.setString(1, LEASE_NAME);
                insert.setString(2, instanceId);
                insert.setLong(3, expiresAt);
                if (insert.executeUpdate() == 1) {
                    return true;
                }
            }
            // Otherwise: renew if this server holds it, or take it if it has expired.
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE lease SET holder = ?, expires_at_ms = ?
                    WHERE name = ? AND (holder = ? OR expires_at_ms <= ?)""")) {
                update.setString(1, instanceId);
                update.setLong(2, expiresAt);
                update.setString(3, LEASE_NAME);
                update.setString(4, instanceId);
                update.setLong(5, now);
                return update.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new StoreException("could not update the lease", e);
        }
    }

    @Override
    public boolean holdsLease() {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT holder, expires_at_ms FROM lease WHERE name = ?")) {
            query.setString(1, LEASE_NAME);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next()
                        && instanceId.equals(rows.getString("holder"))
                        && clock.millis() < rows.getLong("expires_at_ms");
            }
        } catch (SQLException e) {
            throw new StoreException("could not read the lease", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new StoreException("could not close the lease connection", e);
        }
    }
}
