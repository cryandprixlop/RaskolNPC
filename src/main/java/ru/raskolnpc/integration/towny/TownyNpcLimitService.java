package ru.raskolnpc.integration.towny;

import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.registry.NpcRegistry;

import java.util.HashMap;
import java.util.Map;

public final class TownyNpcLimitService {
    private final Map<String, Integer> pendingByTown = new HashMap<>();

    public synchronized Reservation reserve(String townId, int maximum, NpcRegistry registry) {
        if (townId == null || townId.isBlank()) {
            return new Reservation(null);
        }

        long existing = registry.all().stream()
                .filter(record -> record.type() == NpcType.RESIDENT)
                .filter(record -> townId.equals(record.townId()))
                .count();
        int pending = pendingByTown.getOrDefault(townId, 0);
        if (existing + pending >= Math.max(1, maximum)) {
            throw new TownyIntegrationException(
                    "В городе уже достигнут лимит жителей (" + maximum + ").");
        }
        pendingByTown.put(townId, pending + 1);
        return new Reservation(townId);
    }

    public final class Reservation implements AutoCloseable {
        private final String townId;
        private boolean closed;

        private Reservation(String townId) {
            this.townId = townId;
        }

        @Override
        public void close() {
            synchronized (TownyNpcLimitService.this) {
                if (closed || townId == null) return;
                closed = true;
                int remaining = pendingByTown.getOrDefault(townId, 0) - 1;
                if (remaining <= 0) pendingByTown.remove(townId);
                else pendingByTown.put(townId, remaining);
            }
        }
    }
}
