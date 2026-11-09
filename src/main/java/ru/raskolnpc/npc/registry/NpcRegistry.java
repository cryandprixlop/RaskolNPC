package ru.raskolnpc.npc.registry;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class NpcRegistry {

    private final ConcurrentMap<NpcId, NpcRecord> records = new ConcurrentHashMap<>();

    public boolean register(NpcRecord record) {
        return records.putIfAbsent(record.id(), record) == null;
    }

    public void put(NpcRecord record) {
        records.put(record.id(), record);
    }

    public Optional<NpcRecord> get(NpcId id) {
        return Optional.ofNullable(records.get(id));
    }

    public Optional<NpcRecord> unregister(NpcId id) {
        return Optional.ofNullable(records.remove(id));
    }

    public Collection<NpcRecord> all() {
        return List.copyOf(records.values());
    }

    public int size() {
        return records.size();
    }

    public void clear() {
        records.clear();
    }
}
