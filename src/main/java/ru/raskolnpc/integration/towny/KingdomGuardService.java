package ru.raskolnpc.integration.towny;

import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Enforces the per-nation guard limit, including in-flight hiring reservations.
 */
public final class KingdomGuardService {
    private final Map<String, Integer> pendingByNation = new HashMap<>();

    public synchronized Reservation reserve(String nationId, int maximum, NpcRegistry registry) {
        if (nationId == null || nationId.isBlank()) {
            throw new TownyIntegrationException("Стража должна быть привязана к королевству Towny.");
        }

        long existing = registry.all().stream()
                .filter(record -> record.type() == NpcType.GUARD)
                .filter(record -> nationId.equals(record.nationId()))
                .count();
        int pending = pendingByNation.getOrDefault(nationId, 0);
        int limit = Math.max(1, maximum);
        if (existing + pending >= limit) {
            throw new TownyIntegrationException(
                    "В королевстве уже достигнут лимит стражи (" + limit + ").");
        }

        pendingByNation.put(nationId, pending + 1);
        return new Reservation(nationId);
    }

    public final class Reservation implements AutoCloseable {
        private final String nationId;
        private boolean closed;

        private Reservation(String nationId) {
            this.nationId = nationId;
        }

        @Override
        public void close() {
            synchronized (KingdomGuardService.this) {
                if (closed) return;
                closed = true;
                int remaining = pendingByNation.getOrDefault(nationId, 0) - 1;
                if (remaining <= 0) pendingByNation.remove(nationId);
                else pendingByNation.put(nationId, remaining);
            }
        }
    }
}
