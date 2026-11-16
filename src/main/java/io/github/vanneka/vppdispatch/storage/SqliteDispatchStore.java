package io.github.vanneka.vppdispatch.storage;

import io.github.vanneka.vppdispatch.protocol.DeviceCommand;
import io.github.vanneka.vppdispatch.protocol.DispatchRequest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A {@link DispatchStore} backed by a single SQLite file. */
public final class SqliteDispatchStore implements DispatchStore {

    private final Connection connection;

    public SqliteDispatchStore(Path databaseFile) {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            createTablesIfMissing();
        } catch (SQLException e) {
            throw new StoreException("could not open " + databaseFile, e);
        }
    }

    private void createTablesIfMissing() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // SQLite only enforces REFERENCES when this is switched on for the connection.
            statement.execute("PRAGMA foreign_keys = ON");
            // Two platform instances share this file; wait for the other's write to finish
            // instead of failing at once.
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS dispatch (
                        mrid             TEXT PRIMARY KEY,
                        creation_time    TEXT NOT NULL,
                        target_kw        INTEGER NOT NULL,
                        start_time       TEXT NOT NULL,
                        duration_seconds INTEGER NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS device_command (
                        command_id       TEXT PRIMARY KEY,
                        mrid             TEXT NOT NULL REFERENCES dispatch(mrid),
                        device_id        TEXT NOT NULL,
                        target_kw        INTEGER NOT NULL,
                        plan_order       INTEGER NOT NULL,
                        state            TEXT NOT NULL
                    )""");
        }
    }

    @Override
    public void saveDispatch(DispatchRequest request, List<DeviceCommand> commands) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertDispatch = connection.prepareStatement(
                            "INSERT INTO dispatch VALUES (?, ?, ?, ?, ?)");
                    PreparedStatement insertCommand = connection.prepareStatement(
                            "INSERT INTO device_command VALUES (?, ?, ?, ?, ?, ?)")) {
                insertDispatch.setString(1, request.mrid());
                insertDispatch.setString(2, request.creationTime().toString());
                insertDispatch.setInt(3, request.targetKw());
                insertDispatch.setString(4, request.startTime().toString());
                insertDispatch.setLong(5, request.duration().toSeconds());
                insertDispatch.executeUpdate();

                int order = 0;
                for (DeviceCommand command : commands) {
                    insertCommand.setString(1, command.commandId());
                    insertCommand.setString(2, command.mrid());
                    insertCommand.setString(3, command.deviceId());
                    insertCommand.setInt(4, command.targetKw());
                    insertCommand.setInt(5, order++);
                    insertCommand.setString(6, CommandState.PLANNED.name());
                    insertCommand.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("could not save dispatch " + request.mrid(), e);
        }
    }

    @Override
    public boolean hasDispatch(String mrid) {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM dispatch WHERE mrid = ?")) {
            query.setString(1, mrid);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new StoreException("could not read dispatch " + mrid, e);
        }
    }

    @Override
    public boolean markSent(String commandId) {
        return moveForward(commandId, CommandState.SENT);
    }

    @Override
    public boolean markReceived(String commandId) {
        return moveForward(commandId, CommandState.RECEIVED);
    }

    /**
     * Moves a command to {@code newState} only if it is currently in an earlier state.
     * The check is part of the UPDATE itself, so even two platform instances writing
     * to the same file cannot move a command backward (for example, a late "sent"
     * overwriting "received").
     */
    private boolean moveForward(String commandId, CommandState newState) {
        List<CommandState> earlier = newState.earlierStates();
        String placeholders = String.join(", ", Collections.nCopies(earlier.size(), "?"));
        String sql = "UPDATE device_command SET state = ? WHERE command_id = ? AND state IN ("
                + placeholders + ")";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, newState.name());
            update.setString(2, commandId);
            for (int i = 0; i < earlier.size(); i++) {
                update.setString(3 + i, earlier.get(i).name());
            }
            if (update.executeUpdate() == 1) {
                return true;
            }
            requireCommandExists(commandId);
            return false;
        } catch (SQLException e) {
            throw new StoreException("could not update command " + commandId, e);
        }
    }

    private void requireCommandExists(String commandId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM device_command WHERE command_id = ?")) {
            query.setString(1, commandId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new StoreException("no command with id " + commandId, null);
                }
            }
        }
    }

    @Override
    public List<StoredCommand> commandsFor(String mrid) {
        String sql = """
                SELECT c.command_id, c.device_id, c.target_kw, c.state,
                       d.start_time, d.duration_seconds
                FROM device_command c JOIN dispatch d ON d.mrid = c.mrid
                WHERE c.mrid = ?
                ORDER BY c.plan_order""";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, mrid);
            List<StoredCommand> result = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    DeviceCommand command = new DeviceCommand(
                            rows.getString("command_id"),
                            mrid,
                            rows.getString("device_id"),
                            rows.getInt("target_kw"),
                            Instant.parse(rows.getString("start_time")),
                            Duration.ofSeconds(rows.getLong("duration_seconds")));
                    result.add(new StoredCommand(command, CommandState.valueOf(rows.getString("state"))));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("could not read dispatch " + mrid, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new StoreException("could not close database", e);
        }
    }
}
