package ru.raskolnpc.npc.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NpcRecordTest {

    @Test
    void createsRecordWithSafeDefaults() {
        NpcRecord record = NpcRecord.create(NpcType.RESIDENT, "Mira", null, null, null, null);

        assertNotNull(record.id());
        assertEquals(NpcType.RESIDENT, record.type());
        assertEquals("Mira", record.name());
        assertEquals(NpcState.UNLOADED, record.state());
        assertEquals("{}", record.dataJson());
        assertNotNull(record.createdAt());
        assertEquals(record.createdAt(), record.updatedAt());
        assertNull(record.entityUuid());
        assertNull(record.spawnLocation());
    }

    @Test
    void rejectsBlankAndOverlongNames() {
        assertThrows(IllegalArgumentException.class,
                () -> NpcRecord.create(NpcType.RESIDENT, " ", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> NpcRecord.create(NpcType.RESIDENT, "x".repeat(65), null, null, null, null));
    }

    @Test
    void stateAndEntityBindingCopiesPreserveIdentityAndMetadata() {
        UUID owner = UUID.randomUUID();
        NpcRecord original = NpcRecord.create(NpcType.COMPANION, "Ash", owner,
                "town-1", "nation-1", "{\"level\":2}");

        NpcRecord active = original.withState(NpcState.ACTIVE);

        assertEquals(original.id(), active.id());
        assertEquals(original.ownerUuid(), active.ownerUuid());
        assertEquals(original.townId(), active.townId());
        assertEquals(original.nationId(), active.nationId());
        assertEquals(original.dataJson(), active.dataJson());
        assertEquals(NpcState.ACTIVE, active.state());
        assertNull(active.entityUuid());
    }
}
