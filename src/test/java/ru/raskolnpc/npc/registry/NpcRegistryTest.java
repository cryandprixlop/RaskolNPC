package ru.raskolnpc.npc.registry;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import static org.junit.jupiter.api.Assertions.*;

class NpcRegistryTest {

    @Test
    void registersAndFindsRecord() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Sentinel", null, null, null, null);

        assertTrue(registry.register(record));
        assertEquals(record, registry.get(record.id()).orElseThrow());
        assertEquals(1, registry.size());
    }

    @Test
    void preventsDuplicateRegistrationButAllowsExplicitReplacement() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord record = NpcRecord.create(NpcType.GUARD, "Sentinel", null, null, null, null);

        assertTrue(registry.register(record));
        assertFalse(registry.register(record));
        NpcRecord changed = record.withState(ru.raskolnpc.npc.model.NpcState.ACTIVE);
        registry.put(changed);

        assertEquals(changed, registry.get(record.id()).orElseThrow());
        assertEquals(1, registry.size());
    }

    @Test
    void unregistersAndClearsRecords() {
        NpcRegistry registry = new NpcRegistry();
        NpcRecord record = NpcRecord.create(NpcType.RESIDENT, "Mira", null, null, null, null);
        registry.register(record);

        assertEquals(record, registry.unregister(record.id()).orElseThrow());
        assertTrue(registry.get(record.id()).isEmpty());
        assertTrue(registry.all().isEmpty());

        registry.register(record);
        registry.clear();
        assertEquals(0, registry.size());
    }
}
