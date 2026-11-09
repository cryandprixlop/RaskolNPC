package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
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

    private static final String SELECT_COLUMNS =
            "id, type, name, owner_uuid, town_id, nation_id, state, data_json, created_at, updated_at";

    private final DatabaseManager database;

    public SqliteNpcRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Void> insert(NpcRecord record) {
        return database.execute(connection -> {
            String sql = """
                    INSERT INTO npcs (
                        id, type, name, owner_uuid, town_id, nation_id, state, data_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.id().toString());
                statement.setString(2, record.type().name());
                statement.setString(3, record.name());
                setNullable(statement, 4, record.ownerUuid() == null
                        ? null : record.ownerUuid().toString());
                setNullable(statement, 5, record.townId());
                setNullable(statement, 6, record.nationId());
                statement.setString(7, record.state().name());
                statement.setString(8, record.dataJson());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<NpcRecord>> findById(NpcId id) {
        return database.query(connection -> {
            String sql = "SELECT " + SELECT_COLUMNS + " FROM npcs WHERE id = ?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, id.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.of(map(result))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<NpcRecord>> findAll() {
        return database.query(connection -> {
            List<NpcRecord> records = new ArrayList<>();
            String sql = "SELECT " + SELECT_COLUMNS + " FROM npcs ORDER BY created_at, id";
            try (var statement = connection.prepareStatement(sql);
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
                    UPDATE npcs
                    SET type = ?, name = ?, owner_uuid = ?, town_id = ?, nation_id = ?,
                        state = ?, data_json = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.type().name());
                statement.setString(2, record.name());
                setNullable(statement, 3, record.ownerUuid() == null
                        ? null : record.ownerUuid().toString());
                setNullable(statement, 4, record.townId());
                setNullable(statement, 5, record.nationId());
                statement.setString(6, record.state().name());
                statement.setString(7, record.dataJson());
                statement.setString(8, record.id().toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> updateState(NpcId id, NpcState state) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE npcs
                    SET state = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
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
            try (var statement = connection.prepareStatement(
                    "DELETE FROM npcs WHERE id = ?")) {
                statement.setString(1, id.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static NpcRecord map(ResultSet result) throws SQLException {
        String owner = result.getString("owner_uuid");
        return new NpcRecord(
                NpcId.fromString(result.getString("id")),
                NpcType.valueOf(result.getString("type")),
                result.getString("name"),
                owner == null ? null : UUID.fromString(owner),
                result.getString("town_id"),
                result.getString("nation_id"),
                NpcState.valueOf(result.getString("state")),
                result.getString("data_json"),
                result.getString("created_at"),
                result.getString("updated_at")
        );
    }

    private static void setNullable(
            java.sql.PreparedStatement statement, int index, String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
