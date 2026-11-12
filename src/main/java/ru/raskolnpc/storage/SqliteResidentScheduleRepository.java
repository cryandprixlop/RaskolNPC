package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqliteResidentScheduleRepository implements ResidentScheduleRepository {
    private final DatabaseManager database;

    public SqliteResidentScheduleRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Void> saveSchedule(NpcId npcId, ResidentSchedule schedule) {
        return database.transaction(connection -> {
            requireResidentProfile(connection, npcId);
            try (var delete = connection.prepareStatement(
                    "DELETE FROM resident_schedules WHERE npc_id = ?")) {
                delete.setString(1, npcId.toString());
                delete.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO resident_schedules (
                        npc_id, day_of_week, start_minute, end_minute, activity, destination_type
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                for (ResidentSchedule.Entry entry : schedule.entries()) {
                    insert.setString(1, npcId.toString());
                    insert.setInt(2, entry.day().getValue());
                    insert.setInt(3, entry.startMinute());
                    insert.setInt(4, entry.endMinute());
                    insert.setString(5, entry.activity().name());
                    insert.setString(6, entry.destinationType().name());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<ResidentSchedule>> findSchedule(NpcId npcId) {
        return database.query(connection -> {
            List<ResidentSchedule.Entry> entries = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT day_of_week, start_minute, end_minute, activity, destination_type
                    FROM resident_schedules
                    WHERE npc_id = ?
                    ORDER BY day_of_week, start_minute
                    """)) {
                statement.setString(1, npcId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        entries.add(new ResidentSchedule.Entry(
                                DayOfWeek.of(result.getInt("day_of_week")),
                                result.getInt("start_minute"),
                                result.getInt("end_minute"),
                                ResidentSchedule.Activity.valueOf(result.getString("activity")),
                                ResidentDestination.DestinationType.valueOf(
                                        result.getString("destination_type"))));
                    }
                }
            }
            return entries.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new ResidentSchedule(entries));
        });
    }

    @Override
    public CompletableFuture<Void> saveDestination(ResidentDestination destination) {
        return database.transaction(connection -> {
            requireResidentProfile(connection, destination.npcId());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO resident_destinations (
                        npc_id, destination_type, world_uuid, x, y, z, yaw, pitch, label
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(npc_id, destination_type) DO UPDATE SET
                        world_uuid = excluded.world_uuid,
                        x = excluded.x,
                        y = excluded.y,
                        z = excluded.z,
                        yaw = excluded.yaw,
                        pitch = excluded.pitch,
                        label = excluded.label
                    """)) {
                statement.setString(1, destination.npcId().toString());
                statement.setString(2, destination.type().name());
                statement.setString(3, destination.worldId().toString());
                statement.setDouble(4, destination.x());
                statement.setDouble(5, destination.y());
                statement.setDouble(6, destination.z());
                statement.setFloat(7, destination.yaw());
                statement.setFloat(8, destination.pitch());
                statement.setString(9, destination.label());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<ResidentDestination>> findDestinations(NpcId npcId) {
        return database.query(connection -> {
            List<ResidentDestination> destinations = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT destination_type, world_uuid, x, y, z, yaw, pitch, label
                    FROM resident_destinations
                    WHERE npc_id = ?
                    ORDER BY destination_type
                    """)) {
                statement.setString(1, npcId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        destinations.add(new ResidentDestination(
                                npcId,
                                ResidentDestination.DestinationType.valueOf(
                                        result.getString("destination_type")),
                                UUID.fromString(result.getString("world_uuid")),
                                result.getDouble("x"),
                                result.getDouble("y"),
                                result.getDouble("z"),
                                result.getFloat("yaw"),
                                result.getFloat("pitch"),
                                result.getString("label")));
                    }
                }
            }
            return List.copyOf(destinations);
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteDestination(
            NpcId npcId, ResidentDestination.DestinationType type) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement("""
                    DELETE FROM resident_destinations
                    WHERE npc_id = ? AND destination_type = ?
                    """)) {
                statement.setString(1, npcId.toString());
                statement.setString(2, type.name());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static void requireResidentProfile(
            java.sql.Connection connection, NpcId npcId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM resident_profiles WHERE npc_id = ?")) {
            statement.setString(1, npcId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Resident profile does not exist: " + npcId);
                }
            }
        }
    }
}
