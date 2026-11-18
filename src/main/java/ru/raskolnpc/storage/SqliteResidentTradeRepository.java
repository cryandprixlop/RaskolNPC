package ru.raskolnpc.storage;

import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcId;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** SQLite implementation of the durable resident trade journal and stock reservation. */
public final class SqliteResidentTradeRepository implements ResidentTradeRepository {
    private final DatabaseManager database;

    public SqliteResidentTradeRepository(DatabaseManager database) {
        this.database = java.util.Objects.requireNonNull(database, "database");
    }

    @Override
    public CompletableFuture<Optional<ResidentTradeTransaction>> reserve(
            String idempotencyKey, UUID buyerId, NpcId residentId, String material,
            ResidentProductQuality quality, int amount, long unitPriceMinor) {
        String key = requireText(idempotencyKey, "idempotencyKey", 128);
        UUID buyer = java.util.Objects.requireNonNull(buyerId, "buyerId");
        NpcId resident = java.util.Objects.requireNonNull(residentId, "residentId");
        ResidentProductQuality tier = java.util.Objects.requireNonNull(quality, "quality");
        String item = normalizeMaterial(material);
        if (amount < 1 || amount > 1_000_000) throw new IllegalArgumentException("Invalid trade amount.");
        if (unitPriceMinor < 0) throw new IllegalArgumentException("Unit price cannot be negative.");
        long total = Math.multiplyExact(unitPriceMinor, (long) amount);
        long now = System.currentTimeMillis();

        return database.transaction(connection -> {
            Optional<ResidentTradeTransaction> existing = findByKey(connection, key);
            if (existing.isPresent()) {
                ResidentTradeTransaction prior = existing.get();
                if (!prior.buyerId().equals(buyer) || !prior.residentId().equals(resident)
                        || !prior.material().equals(item) || prior.quality() != tier
                        || prior.amount() != amount || prior.unitPriceMinor() != unitPriceMinor) {
                    throw new SQLException("Idempotency key was reused with different trade parameters.");
                }
                return existing;
            }

            String businessId;
            String profession;
            try (var statement = connection.prepareStatement("""
                    SELECT n.town_id, rp.profession
                    FROM npcs n JOIN resident_profiles rp ON rp.npc_id = n.id
                    WHERE n.id = ?
                    """)) {
                statement.setString(1, resident.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    String townId = result.getString("town_id");
                    businessId = townId == null || townId.isBlank()
                            ? "resident:" + resident : "town:" + townId;
                    profession = result.getString("profession");
                }
            }

            if (!consumeQualityBatches(connection, businessId, profession, item, tier, amount)) {
                return Optional.empty();
            }
            try (var update = connection.prepareStatement("""
                    UPDATE resident_business_stock
                    SET amount = amount - ?, updated_at = ?
                    WHERE business_id = ? AND profession = ? AND material = ? AND amount >= ?
                    """)) {
                update.setInt(1, amount);
                update.setLong(2, now);
                update.setString(3, businessId);
                update.setString(4, profession);
                update.setString(5, item);
                update.setInt(6, amount);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Aggregate stock changed during trade reservation.");
                }
            }

            String transactionId = UUID.randomUUID().toString();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO resident_trade_transactions(
                        transaction_id, idempotency_key, buyer_uuid, resident_npc_id,
                        material, quality, amount, unit_price_minor, total_price_minor,
                        status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'STOCK_RESERVED', ?, ?)
                    """)) {
                insert.setString(1, transactionId);
                insert.setString(2, key);
                insert.setString(3, buyer.toString());
                insert.setString(4, resident.toString());
                insert.setString(5, item);
                insert.setString(6, tier.name());
                insert.setInt(7, amount);
                insert.setLong(8, unitPriceMinor);
                insert.setLong(9, total);
                insert.setLong(10, now);
                insert.setLong(11, now);
                insert.executeUpdate();
            }
            return findById(connection, transactionId);
        });
    }

    @Override
    public CompletableFuture<Boolean> transition(
            String transactionId, ResidentTradeTransaction.Status expected,
            ResidentTradeTransaction.Status next, String providerReference, String error) {
        String id = requireText(transactionId, "transactionId", 64);
        java.util.Objects.requireNonNull(expected, "expected");
        java.util.Objects.requireNonNull(next, "next");
        if (!allowed(expected, next)) {
            throw new IllegalArgumentException("Illegal trade transition: " + expected + " -> " + next);
        }
        String reference = optionalText(providerReference, 256);
        String message = optionalText(error, 1024);
        return database.transaction(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE resident_trade_transactions
                    SET status = ?, provider_reference = COALESCE(?, provider_reference),
                        last_error = ?, updated_at = ?
                    WHERE transaction_id = ? AND status = ?
                    """)) {
                update.setString(1, next.name());
                update.setString(2, reference);
                update.setString(3, message);
                update.setLong(4, System.currentTimeMillis());
                update.setString(5, id);
                update.setString(6, expected.name());
                return update.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> cancelAndRelease(String transactionId, String reason) {
        String id = requireText(transactionId, "transactionId", 64);
        String message = optionalText(reason, 1024);
        return database.transaction(connection -> {
            ResidentTradeTransaction trade = findById(connection, id).orElse(null);
            if (trade == null || (trade.status() != ResidentTradeTransaction.Status.STOCK_RESERVED
                    && trade.status() != ResidentTradeTransaction.Status.PAYMENT_PENDING)) {
                return false;
            }
            String businessId;
            String profession;
            try (var query = connection.prepareStatement("""
                    SELECT n.town_id, rp.profession
                    FROM npcs n JOIN resident_profiles rp ON rp.npc_id = n.id
                    WHERE n.id = ?
                    """)) {
                query.setString(1, trade.residentId().toString());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new SQLException("Trade resident no longer exists.");
                    String townId = result.getString("town_id");
                    businessId = townId == null || townId.isBlank()
                            ? "resident:" + trade.residentId() : "town:" + townId;
                    profession = result.getString("profession");
                }
            }
            long now = System.currentTimeMillis();
            try (var credit = connection.prepareStatement("""
                    UPDATE resident_business_stock
                    SET amount = amount + ?, updated_at = ?
                    WHERE business_id = ? AND profession = ? AND material = ?
                      AND amount + ? <= capacity
                    """)) {
                credit.setInt(1, trade.amount());
                credit.setLong(2, now);
                credit.setString(3, businessId);
                credit.setString(4, profession);
                credit.setString(5, trade.material());
                credit.setInt(6, trade.amount());
                if (credit.executeUpdate() != 1) {
                    throw new SQLException("Could not restore reserved stock within business capacity.");
                }
            }
            try (var batch = connection.prepareStatement("""
                    INSERT INTO resident_business_stock_batches(
                        batch_id, business_id, profession, material, amount,
                        quality, created_at, source_type, source_ref
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'TRADE_RELEASE', ?)
                    """)) {
                batch.setString(1, UUID.randomUUID().toString());
                batch.setString(2, businessId);
                batch.setString(3, profession);
                batch.setString(4, trade.material());
                batch.setInt(5, trade.amount());
                batch.setString(6, trade.quality().name());
                batch.setLong(7, now);
                batch.setString(8, trade.transactionId());
                batch.executeUpdate();
            }
            try (var update = connection.prepareStatement("""
                    UPDATE resident_trade_transactions
                    SET status = 'CANCELLED', last_error = ?, updated_at = ?
                    WHERE transaction_id = ? AND status IN ('STOCK_RESERVED', 'PAYMENT_PENDING')
                    """)) {
                update.setString(1, message);
                update.setLong(2, now);
                update.setString(3, id);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Trade state changed while releasing reserved stock.");
                }
            }
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> completeRefundAndRelease(
            String transactionId, String providerReference, String detail) {
        String id = requireText(transactionId, "transactionId", 64);
        String reference = optionalText(providerReference, 256);
        String message = optionalText(detail, 1024);
        return database.transaction(connection -> {
            ResidentTradeTransaction trade = findById(connection, id).orElse(null);
            if (trade == null || trade.status() != ResidentTradeTransaction.Status.REFUND_PENDING) {
                return false;
            }
            String businessId;
            String profession;
            try (var query = connection.prepareStatement("""
                    SELECT n.town_id, rp.profession
                    FROM npcs n JOIN resident_profiles rp ON rp.npc_id = n.id
                    WHERE n.id = ?
                    """)) {
                query.setString(1, trade.residentId().toString());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new SQLException("Trade resident no longer exists.");
                    String townId = result.getString("town_id");
                    businessId = townId == null || townId.isBlank()
                            ? "resident:" + trade.residentId() : "town:" + townId;
                    profession = result.getString("profession");
                }
            }
            long now = System.currentTimeMillis();
            try (var credit = connection.prepareStatement("""
                    UPDATE resident_business_stock
                    SET amount = amount + ?, updated_at = ?
                    WHERE business_id = ? AND profession = ? AND material = ?
                      AND amount + ? <= capacity
                    """)) {
                credit.setInt(1, trade.amount());
                credit.setLong(2, now);
                credit.setString(3, businessId);
                credit.setString(4, profession);
                credit.setString(5, trade.material());
                credit.setInt(6, trade.amount());
                if (credit.executeUpdate() != 1) {
                    throw new SQLException("Cannot restore refunded trade stock: business capacity is insufficient.");
                }
            }
            try (var batch = connection.prepareStatement("""
                    INSERT INTO resident_business_stock_batches(
                        batch_id, business_id, profession, material, amount,
                        quality, created_at, source_type, source_ref
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'TRADE_REFUND', ?)
                    """)) {
                batch.setString(1, UUID.randomUUID().toString());
                batch.setString(2, businessId);
                batch.setString(3, profession);
                batch.setString(4, trade.material());
                batch.setInt(5, trade.amount());
                batch.setString(6, trade.quality().name());
                batch.setLong(7, now);
                batch.setString(8, trade.transactionId());
                batch.executeUpdate();
            }
            try (var update = connection.prepareStatement("""
                    UPDATE resident_trade_transactions
                    SET status = 'REFUNDED', provider_reference = ?, last_error = ?, updated_at = ?
                    WHERE transaction_id = ? AND status = 'REFUND_PENDING'
                    """)) {
                update.setString(1, reference);
                update.setString(2, message);
                update.setLong(3, now);
                update.setString(4, id);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Trade state changed while finalizing refund.");
                }
            }
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> resolveReview(
            String transactionId, ReviewResolution resolution, String evidence) {
        String id = requireText(transactionId, "transactionId", 64);
        java.util.Objects.requireNonNull(resolution, "resolution");
        String proof = requireText(evidence, "evidence", 1024);
        return database.transaction(connection -> {
            ResidentTradeTransaction trade = findById(connection, id).orElse(null);
            if (trade == null || trade.status() != ResidentTradeTransaction.Status.REVIEW_REQUIRED) {
                return false;
            }

            // Only decisions proving that no item was delivered restore the reserved stock.
            if (resolution != ReviewResolution.DELIVERED) {
                String businessId;
                String profession;
                try (var query = connection.prepareStatement("""
                        SELECT n.town_id, rp.profession
                        FROM npcs n JOIN resident_profiles rp ON rp.npc_id = n.id
                        WHERE n.id = ?
                        """)) {
                    query.setString(1, trade.residentId().toString());
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) throw new SQLException("Trade resident no longer exists.");
                        String townId = result.getString("town_id");
                        businessId = townId == null || townId.isBlank()
                                ? "resident:" + trade.residentId() : "town:" + townId;
                        profession = result.getString("profession");
                    }
                }
                long now = System.currentTimeMillis();
                try (var credit = connection.prepareStatement("""
                        UPDATE resident_business_stock
                        SET amount = amount + ?, updated_at = ?
                        WHERE business_id = ? AND profession = ? AND material = ?
                          AND amount + ? <= capacity
                        """)) {
                    credit.setInt(1, trade.amount());
                    credit.setLong(2, now);
                    credit.setString(3, businessId);
                    credit.setString(4, profession);
                    credit.setString(5, trade.material());
                    credit.setInt(6, trade.amount());
                    if (credit.executeUpdate() != 1) {
                        throw new SQLException("Cannot restore reserved stock: business capacity is insufficient.");
                    }
                }
                try (var batch = connection.prepareStatement("""
                        INSERT INTO resident_business_stock_batches(
                            batch_id, business_id, profession, material, amount,
                            quality, created_at, source_type, source_ref
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'TRADE_RECONCILIATION', ?)
                        """)) {
                    batch.setString(1, UUID.randomUUID().toString());
                    batch.setString(2, businessId);
                    batch.setString(3, profession);
                    batch.setString(4, trade.material());
                    batch.setInt(5, trade.amount());
                    batch.setString(6, trade.quality().name());
                    batch.setLong(7, now);
                    batch.setString(8, trade.transactionId());
                    batch.executeUpdate();
                }
            }

            ResidentTradeTransaction.Status target = switch (resolution) {
                case DELIVERED -> ResidentTradeTransaction.Status.DELIVERED;
                case CANCELLED_UNPAID -> ResidentTradeTransaction.Status.CANCELLED;
                case REFUNDED_NOT_DELIVERED -> ResidentTradeTransaction.Status.REFUNDED;
            };
            try (var update = connection.prepareStatement("""
                    UPDATE resident_trade_transactions
                    SET status = ?, last_error = ?, updated_at = ?
                    WHERE transaction_id = ? AND status = 'REVIEW_REQUIRED'
                    """)) {
                update.setString(1, target.name());
                update.setString(2, "Administrator reconciliation: " + proof);
                update.setLong(3, System.currentTimeMillis());
                update.setString(4, id);
                return update.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ResidentTradeTransaction>> find(String transactionId) {
        String id = requireText(transactionId, "transactionId", 64);
        return database.query(connection -> findById(connection, id));
    }

    @Override
    public CompletableFuture<java.util.List<ResidentTradeTransaction>> findByStatus(
            ResidentTradeTransaction.Status status, int limit) {
        java.util.Objects.requireNonNull(status, "status");
        int boundedLimit = Math.max(1, Math.min(100, limit));
        return database.query(connection -> {
            java.util.ArrayList<ResidentTradeTransaction> transactions = new java.util.ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT * FROM resident_trade_transactions
                    WHERE status = ?
                    ORDER BY created_at, transaction_id
                    LIMIT ?
                    """)) {
                query.setString(1, status.name());
                query.setInt(2, boundedLimit);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) transactions.add(read(result));
                }
            }
            return java.util.List.copyOf(transactions);
        });
    }

    @Override
    public CompletableFuture<Optional<ResidentTradeTransaction>> findByIdempotencyKey(String idempotencyKey) {
        String key = requireText(idempotencyKey, "idempotencyKey", 128);
        return database.query(connection -> findByKey(connection, key));
    }

    @Override
    public CompletableFuture<Integer> recoverUnfinished() {
        return database.transaction(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE resident_trade_transactions
                    SET status = 'REVIEW_REQUIRED',
                        last_error = 'Recovered after restart; payment and delivery outcome requires reconciliation.',
                        updated_at = ?
                    WHERE status IN ('STOCK_RESERVED', 'PAYMENT_PENDING', 'PAID', 'REFUND_PENDING')
                    """)) {
                update.setLong(1, System.currentTimeMillis());
                return update.executeUpdate();
            }
        });
    }

    private static boolean consumeQualityBatches(Connection connection, String businessId,
            String profession, String material, ResidentProductQuality quality, int amount)
            throws SQLException {
        record Slice(String id, int amount) { }
        java.util.ArrayList<Slice> selected = new java.util.ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT batch_id, amount
                FROM resident_business_stock_batches
                WHERE business_id = ? AND profession = ? AND material = ? AND quality = ?
                ORDER BY created_at, batch_id
                """)) {
            query.setString(1, businessId);
            query.setString(2, profession);
            query.setString(3, material);
            query.setString(4, quality.name());
            try (ResultSet result = query.executeQuery()) {
                int remaining = amount;
                while (result.next() && remaining > 0) {
                    int taken = Math.min(result.getInt("amount"), remaining);
                    selected.add(new Slice(result.getString("batch_id"), taken));
                    remaining -= taken;
                }
                if (remaining > 0) return false;
            }
        }
        for (Slice slice : selected) {
            try (var update = connection.prepareStatement("""
                    UPDATE resident_business_stock_batches
                    SET amount = amount - ?
                    WHERE batch_id = ? AND amount > ?
                    """)) {
                update.setInt(1, slice.amount());
                update.setString(2, slice.id());
                update.setInt(3, slice.amount());
                int changed = update.executeUpdate();
                if (changed == 0) {
                    try (var delete = connection.prepareStatement("""
                            DELETE FROM resident_business_stock_batches
                            WHERE batch_id = ? AND amount = ?
                            """)) {
                        delete.setString(1, slice.id());
                        delete.setInt(2, slice.amount());
                        if (delete.executeUpdate() != 1) {
                            throw new SQLException("Stock batch changed during trade reservation.");
                        }
                    }
                }
            }
        }
        return true;
    }

    private static Optional<ResidentTradeTransaction> findById(Connection connection, String id)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT * FROM resident_trade_transactions WHERE transaction_id = ?
                """)) {
            query.setString(1, id);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static Optional<ResidentTradeTransaction> findByKey(Connection connection, String key)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT * FROM resident_trade_transactions WHERE idempotency_key = ?
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static ResidentTradeTransaction read(ResultSet result) throws SQLException {
        return new ResidentTradeTransaction(
                result.getString("transaction_id"), result.getString("idempotency_key"),
                UUID.fromString(result.getString("buyer_uuid")),
                NpcId.fromString(result.getString("resident_npc_id")),
                result.getString("material"),
                ResidentProductQuality.valueOf(result.getString("quality")),
                result.getInt("amount"), result.getLong("unit_price_minor"),
                result.getLong("total_price_minor"),
                ResidentTradeTransaction.Status.valueOf(result.getString("status")),
                result.getString("provider_reference"), result.getString("last_error"),
                result.getLong("created_at"), result.getLong("updated_at"));
    }

    private static boolean allowed(ResidentTradeTransaction.Status from,
            ResidentTradeTransaction.Status to) {
        return switch (from) {
            case STOCK_RESERVED -> to == ResidentTradeTransaction.Status.PAYMENT_PENDING
                    || to == ResidentTradeTransaction.Status.CANCELLED
                    || to == ResidentTradeTransaction.Status.REVIEW_REQUIRED;
            case PAYMENT_PENDING -> to == ResidentTradeTransaction.Status.PAID
                    || to == ResidentTradeTransaction.Status.CANCELLED
                    || to == ResidentTradeTransaction.Status.REVIEW_REQUIRED;
            case PAID -> to == ResidentTradeTransaction.Status.DELIVERED
                    || to == ResidentTradeTransaction.Status.REFUND_PENDING
                    || to == ResidentTradeTransaction.Status.REVIEW_REQUIRED;
            case REFUND_PENDING -> to == ResidentTradeTransaction.Status.REFUNDED
                    || to == ResidentTradeTransaction.Status.REVIEW_REQUIRED;
            case REVIEW_REQUIRED -> to == ResidentTradeTransaction.Status.DELIVERED
                    || to == ResidentTradeTransaction.Status.CANCELLED
                    || to == ResidentTradeTransaction.Status.REFUNDED;
            case DELIVERED, CANCELLED, REFUNDED -> false;
        };
    }

    private static String normalizeMaterial(String value) {
        String normalized = requireText(value, "material", 64).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid material.");
        return normalized;
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null) throw new IllegalArgumentException(name + " cannot be null.");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1-" + maxLength + " characters.");
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        return requireText(value, "value", maxLength);
    }
}
