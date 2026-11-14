package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.npc.model.NpcId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SqliteGuardOrderRepository implements GuardOrderRepository {
    private final DatabaseManager database;

    public SqliteGuardOrderRepository(DatabaseManager database) {
        this.database = java.util.Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletableFuture<Boolean> save(GuardOrder order) {
        java.util.Objects.requireNonNull(order, "order");
        return database.transaction(connection -> {
            try (var check = connection.prepareStatement(
                    "SELECT 1 FROM npcs WHERE id = ? AND type = 'GUARD'")) {
                check.setString(1, order.npcId().toString());
                try (ResultSet result = check.executeQuery()) {
                    if (!result.next()) return false;
                }
            }

            try (var statement = connection.prepareStatement("""
                    INSERT INTO guard_orders(npc_id, order_type, issuer_uuid, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(npc_id) DO UPDATE SET
                        order_type = excluded.order_type,
                        issuer_uuid = excluded.issuer_uuid,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, order.npcId().toString());
                statement.setString(2, order.type().name());
                statement.setString(3, order.issuedBy().toString());
                statement.setLong(4, order.updatedAt());
                statement.executeUpdate();
            }

            try (var delete = connection.prepareStatement(
                    "DELETE FROM guard_order_waypoints WHERE npc_id = ?")) {
                delete.setString(1, order.npcId().toString());
                delete.executeUpdate();
            }

            try (var insert = connection.prepareStatement("""
                    INSERT INTO guard_order_waypoints(
                        npc_id, sequence, world_uuid, x, y, z, yaw, pitch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (int index = 0; index < order.waypoints().size(); index++) {
                    GuardOrder.Waypoint point = order.waypoints().get(index);
                    insert.setString(1, order.npcId().toString());
                    insert.setInt(2, index);
                    insert.setString(3, point.worldId().toString());
                    insert.setDouble(4, point.x());
                    insert.setDouble(5, point.y());
                    insert.setDouble(6, point.z());
                    insert.setFloat(7, point.yaw());
                    insert.setFloat(8, point.pitch());
                    insert.addBatch();
                }
                if (!order.waypoints().isEmpty()) insert.executeBatch();
            }
            return true;
        });
    }

    @Override
    public CompletableFuture<Optional<GuardOrder>> find(NpcId npcId) {
        java.util.Objects.requireNonNull(npcId, "npcId");
        return database.query(connection -> {
            String type;
            UUID issuer;
            long updatedAt;
            try (var statement = connection.prepareStatement("""
                    SELECT order_type, issuer_uuid, updated_at
                    FROM guard_orders WHERE npc_id = ?
                    """)) {
                statement.setString(1, npcId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    type = result.getString("order_type");
                    issuer = UUID.fromString(result.getString("issuer_uuid"));
                    updatedAt = result.getLong("updated_at");
                }
            }

            List<GuardOrder.Waypoint> waypoints = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT world_uuid, x, y, z, yaw, pitch
                    FROM guard_order_waypoints
                    WHERE npc_id = ? ORDER BY sequence
                    """)) {
                statement.setString(1, npcId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        waypoints.add(new GuardOrder.Waypoint(
                                UUID.fromString(result.getString("world_uuid")),
                                result.getDouble("x"), result.getDouble("y"),
                                result.getDouble("z"), result.getFloat("yaw"),
                                result.getFloat("pitch")));
                    }
                }
            }
            return Optional.of(new GuardOrder(npcId, GuardOrder.Type.valueOf(type),
                    waypoints, issuer, updatedAt));
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(NpcId npcId) {
        java.util.Objects.requireNonNull(npcId, "npcId");
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM guard_orders WHERE npc_id = ?")) {
                statement.setString(1, npcId.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }
}
