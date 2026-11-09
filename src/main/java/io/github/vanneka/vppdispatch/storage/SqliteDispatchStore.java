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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS dispatch (
                        dispatch_id      TEXT PRIMARY KEY,
                        target_kw        INTEGER NOT NULL,
                        start_time       TEXT NOT NULL,
                        duration_seconds INTEGER NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS device_command (
                        command_id       TEXT PRIMARY KEY,
                        dispatch_id      TEXT NOT NULL REFERENCES dispatch(dispatch_id),
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
                            "INSERT INTO dispatch VALUES (?, ?, ?, ?)");
                    PreparedStatement insertCommand = connection.prepareStatement(
                            "INSERT INTO device_command VALUES (?, ?, ?, ?, ?, ?)")) {
                insertDispatch.setString(1, request.dispatchId());
                insertDispatch.setInt(2, request.targetKw());
                insertDispatch.setString(3, request.startTime().toString());
                insertDispatch.setLong(4, request.duration().toSeconds());
                insertDispatch.executeUpdate();

                int order = 0;
                for (DeviceCommand command : commands) {
                    insertCommand.setString(1, command.commandId());
                    insertCommand.setString(2, command.dispatchId());
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
            throw new StoreException("could not save dispatch " + request.dispatchId(), e);
        }
    }

    @Override
    public void markSent(String commandId) {
        updateState(commandId, CommandState.SENT);
    }

    @Override
    public void markResponded(String commandId) {
        updateState(commandId, CommandState.RESPONDED);
    }

    private void updateState(String commandId, CommandState newState) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE device_command SET state = ? WHERE command_id = ?")) {
            update.setString(1, newState.name());
            update.setString(2, commandId);
            if (update.executeUpdate() != 1) {
                throw new StoreException("no command with id " + commandId, null);
            }
        } catch (SQLException e) {
            throw new StoreException("could not update command " + commandId, e);
        }
    }

    @Override
    public List<StoredCommand> commandsFor(String dispatchId) {
        String sql = """
                SELECT c.command_id, c.device_id, c.target_kw, c.state,
                       d.start_time, d.duration_seconds
                FROM device_command c JOIN dispatch d ON d.dispatch_id = c.dispatch_id
                WHERE c.dispatch_id = ?
                ORDER BY c.plan_order""";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, dispatchId);
            List<StoredCommand> result = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    DeviceCommand command = new DeviceCommand(
                            rows.getString("command_id"),
                            dispatchId,
                            rows.getString("device_id"),
                            rows.getInt("target_kw"),
                            Instant.parse(rows.getString("start_time")),
                            Duration.ofSeconds(rows.getLong("duration_seconds")));
                    result.add(new StoredCommand(command, CommandState.valueOf(rows.getString("state"))));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new StoreException("could not read dispatch " + dispatchId, e);
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
