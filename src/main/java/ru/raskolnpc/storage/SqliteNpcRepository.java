package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;
import ru.raskolnpc.npc.model.NpcType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqliteNpcRepository implements NpcRepository {
    private static final String SELECT_COLUMNS = """
            id, type, name, owner_uuid, town_id, nation_id, state, data_json,
            created_at, updated_at, entity_uuid, spawn_world_uuid,
            spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch
            """;

    private final DatabaseManager database;

    public SqliteNpcRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Void> insert(NpcRecord record) {
        return database.execute(connection -> {
            String sql = """
                    INSERT INTO npcs (
                        id, type, name, owner_uuid, town_id, nation_id, state, data_json,
                        entity_uuid, spawn_world_uuid, spawn_x, spawn_y, spawn_z,
                        spawn_yaw, spawn_pitch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.id().toString());
                statement.setString(2, record.type().name());
                statement.setString(3, record.name());
                setNullable(statement, 4, uuid(record.ownerUuid()));
                setNullable(statement, 5, record.townId());
                setNullable(statement, 6, record.nationId());
                statement.setString(7, record.state().name());
                statement.setString(8, record.dataJson());
                // Persist the spawn point even when entity_uuid is still null.
                bindEntityLocation(statement, record, 9);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<NpcRecord>> findById(NpcId id) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM npcs WHERE id = ?")) {
                statement.setString(1, id.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(map(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<NpcRecord>> findAll() {
        return database.query(connection -> {
            List<NpcRecord> records = new ArrayList<>();
            try (var statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS + " FROM npcs ORDER BY created_at, id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(map(result));
                }
            }
            return List.copyOf(records);
        });
    }

    @Override
    public CompletableFuture<Boolean> update(NpcRecord record) {
        return database.query(connection -> {
            String sql = """
                    UPDATE npcs SET type = ?, name = ?, owner_uuid = ?, town_id = ?,
                        nation_id = ?, state = ?, data_json = ?, entity_uuid = ?,
                        spawn_world_uuid = ?, spawn_x = ?, spawn_y = ?, spawn_z = ?,
                        spawn_yaw = ?, spawn_pitch = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.type().name());
                statement.setString(2, record.name());
                setNullable(statement, 3, uuid(record.ownerUuid()));
                setNullable(statement, 4, record.townId());
                setNullable(statement, 5, record.nationId());
                statement.setString(6, record.state().name());
                statement.setString(7, record.dataJson());
                bindEntityLocation(statement, record, 8);
                statement.setString(15, record.id().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> updateEntityLocationIfBindingMatches(
            NpcId id, UUID expectedEntityUuid, NpcSpawnLocation location) {
        if (expectedEntityUuid == null) {
            throw new IllegalArgumentException("Expected entity UUID must not be null.");
        }
        if (location == null) {
            throw new IllegalArgumentException("Entity location must not be null.");
        }
        return database.query(connection -> {
            String sql = """
                    UPDATE npcs SET spawn_world_uuid = ?, spawn_x = ?, spawn_y = ?,
                        spawn_z = ?, spawn_yaw = ?, spawn_pitch = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND entity_uuid = ?
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, location.worldId().toString());
                statement.setDouble(2, location.x());
                statement.setDouble(3, location.y());
                statement.setDouble(4, location.z());
                statement.setFloat(5, location.yaw());
                statement.setFloat(6, location.pitch());
                statement.setString(7, id.toString());
                statement.setString(8, expectedEntityUuid.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> updateState(NpcId id, NpcState state) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE npcs SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """)) {
                statement.setString(1, state.name());
                statement.setString(2, id.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(NpcId id) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement("DELETE FROM npcs WHERE id = ?")) {
                statement.setString(1, id.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static NpcRecord map(ResultSet result) throws SQLException {
        String owner = result.getString("owner_uuid");
        String entity = result.getString("entity_uuid");
        String world = result.getString("spawn_world_uuid");
        NpcSpawnLocation location = null;
        if (world != null) {
            location = new NpcSpawnLocation(UUID.fromString(world),
                    result.getDouble("spawn_x"), result.getDouble("spawn_y"),
                    result.getDouble("spawn_z"), result.getFloat("spawn_yaw"),
                    result.getFloat("spawn_pitch"));
        }
        return new NpcRecord(
                NpcId.fromString(result.getString("id")),
                NpcType.valueOf(result.getString("type")),
                result.getString("name"),
                owner == null ? null : UUID.fromString(owner),
                result.getString("town_id"), result.getString("nation_id"),
                NpcState.valueOf(result.getString("state")), result.getString("data_json"),
                result.getString("created_at"), result.getString("updated_at"),
                entity == null ? null : UUID.fromString(entity), location
        );
    }

    private static void bindEntityLocation(
            java.sql.PreparedStatement statement, NpcRecord record, int start
    ) throws SQLException {
        setNullable(statement, start, uuid(record.entityUuid()));
        NpcSpawnLocation location = record.spawnLocation();
        setNullable(statement, start + 1,
                location == null ? null : location.worldId().toString());
        if (location == null) {
            for (int i = 2; i <= 6; i++) {
                statement.setNull(start + i, Types.REAL);
            }
        } else {
            statement.setDouble(start + 2, location.x());
            statement.setDouble(start + 3, location.y());
            statement.setDouble(start + 4, location.z());
            statement.setFloat(start + 5, location.yaw());
            statement.setFloat(start + 6, location.pitch());
        }
    }

    private static String uuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static void setNullable(java.sql.PreparedStatement statement,
                                    int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }
}
