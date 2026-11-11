package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcId;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SqliteNpcDeletionRepository implements NpcDeletionRepository {
    private static final String BLOCKED_DELETION_MESSAGE =
            "Cannot delete guard while rent payment requires reconciliation: ";

    private final DatabaseManager database;

    public SqliteNpcDeletionRepository(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Boolean> deleteAndQueue(NpcId id) {
        return database.transaction(connection -> {
            boolean isGuard;
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM npcs WHERE id = ? AND type = 'GUARD'")) {
                statement.setString(1, id.toString());
                try (var result = statement.executeQuery()) {
                    isGuard = result.next();
                }
            }

            if (isGuard) {
                try (var statement = connection.prepareStatement("""
                        SELECT payment_id
                        FROM guard_rent_payments
                        WHERE npc_id = ?
                          AND status IN ('IN_PROGRESS', 'PAID', 'FAILED', 'REVIEW_REQUIRED')
                        ORDER BY rent_due_at, payment_id
                        LIMIT 1
                        """)) {
                    statement.setString(1, id.toString());
                    try (var result = statement.executeQuery()) {
                        if (result.next()) {
                            throw new SQLException(BLOCKED_DELETION_MESSAGE
                                    + result.getString("payment_id"));
                        }
                    }
                }
            }

            int queued;
            try (var statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO pending_npc_deletions(npc_id)
                    SELECT id FROM npcs WHERE id = ?
                    """)) {
                statement.setString(1, id.toString());
                queued = statement.executeUpdate();
            }
            if (queued == 0) return false;

            try (var statement = connection.prepareStatement(
                    "DELETE FROM npcs WHERE id = ?")) {
                statement.setString(1, id.toString());
                if (statement.executeUpdate() == 0) {
                    throw new SQLException("NPC disappeared during deletion: " + id);
                }
            }
            return true;
        });
    }

    @Override
    public CompletableFuture<List<NpcId>> findPending() {
        return database.query(connection -> {
            List<NpcId> ids = new ArrayList<>();
            try (var statement = connection.prepareStatement(
                    "SELECT npc_id FROM pending_npc_deletions ORDER BY queued_at, npc_id");
                 var result = statement.executeQuery()) {
                while (result.next()) ids.add(NpcId.fromString(result.getString(1)));
            }
            return List.copyOf(ids);
        });
    }

    @Override
    public CompletableFuture<Boolean> complete(NpcId id) {
        return database.query(connection -> {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM pending_npc_deletions WHERE npc_id = ?")) {
                statement.setString(1, id.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }
}
