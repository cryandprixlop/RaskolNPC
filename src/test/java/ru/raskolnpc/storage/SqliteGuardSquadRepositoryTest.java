package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteGuardSquadRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository npcs;
    private SqliteGuardSquadRepository squads;
    private SqliteGuardOrderRepository orders;
    private SqliteNpcDeletionRepository deletions;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("guard-squad-test"));
        database = new DatabaseManager(plugin, "guard-squads.sqlite", 1);
        database.initialize().join();
        npcs = new SqliteNpcRepository(database);
        squads = new SqliteGuardSquadRepository(database);
        orders = new SqliteGuardOrderRepository(database);
        deletions = new SqliteNpcDeletionRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void createsSquadAndPersistsItsGuardMembers() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        UUID squadId = UUID.randomUUID();
        assertTrue(squads.create(squadId, "North Watch", UUID.randomUUID(), 1234L).join());
        assertTrue(squads.addMember(squadId, guard.id(), 10).join());

        var squad = squads.findAll().join().getFirst();
        assertEquals(squadId, squad.id());
        assertEquals("North Watch", squad.name());
        assertEquals(java.util.List.of(guard.id()), squad.members());
    }

    @Test
    void rejectsNonGuardsAndDuplicateSquadMembership() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        NpcRecord resident = createNpc(NpcType.RESIDENT);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        squads.create(first, "First", UUID.randomUUID(), 1234L).join();
        squads.create(second, "Second", UUID.randomUUID(), 1235L).join();

        assertFalse(squads.addMember(first, resident.id(), 10).join());
        assertTrue(squads.addMember(first, guard.id(), 10).join());
        assertFalse(squads.addMember(second, guard.id(), 10).join());
    }

    @Test
    void enforcesSquadCapacityAndCascadesDeletion() {
        NpcRecord firstGuard = createNpc(NpcType.GUARD);
        NpcRecord secondGuard = createNpc(NpcType.GUARD);
        UUID squadId = UUID.randomUUID();
        squads.create(squadId, "Small", UUID.randomUUID(), 1234L).join();

        assertTrue(squads.addMember(squadId, firstGuard.id(), 1).join());
        assertFalse(squads.addMember(squadId, secondGuard.id(), 1).join());
        assertTrue(squads.delete(squadId).join());
        assertTrue(squads.findAll().join().isEmpty());
    }

    @Test
    void deletingGuardCascadesItsOrderAndSquadMembership() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        UUID squadId = UUID.randomUUID();
        squads.create(squadId, "Watch", UUID.randomUUID(), 1234L).join();
        assertTrue(squads.addMember(squadId, guard.id(), 10).join());
        var order = new ru.raskolnpc.guard.model.GuardOrder(
                guard.id(), ru.raskolnpc.guard.model.GuardOrder.Type.RALLY,
                java.util.List.of(new ru.raskolnpc.guard.model.GuardOrder.Waypoint(
                        UUID.randomUUID(), 1, 64, 1, 0, 0)),
                UUID.randomUUID(), 123456L);
        assertTrue(orders.save(order).join());

        assertTrue(deletions.deleteAndQueue(guard.id()).join());

        assertTrue(orders.find(guard.id()).join().isEmpty());
        var persistedSquad = squads.findAll().join().getFirst();
        assertEquals(squadId, persistedSquad.id());
        assertTrue(persistedSquad.members().isEmpty());
    }

    private NpcRecord createNpc(NpcType type) {
        NpcRecord record = NpcRecord.create(type, type.name(), null, "town", "nation", "{}");
        npcs.insert(record).join();
        return record;
    }
}