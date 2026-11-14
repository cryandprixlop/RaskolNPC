package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteGuardOrderRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository npcs;
    private SqliteGuardOrderRepository orders;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("guard-order-test"));
        database = new DatabaseManager(plugin, "guard-orders.sqlite", 1);
        database.initialize().join();
        npcs = new SqliteNpcRepository(database);
        orders = new SqliteGuardOrderRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void savesAndLoadsPatrolWaypointsInSequence() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        UUID world = UUID.randomUUID();
        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(point(world, 1, 2, 3), point(world, 4, 5, 6)),
                UUID.randomUUID(), 123456L);

        assertTrue(orders.save(order).join());
        assertEquals(order, orders.find(guard.id()).join().orElseThrow());
    }

    @Test
    void replacingAnOrderReplacesItsWaypointsAtomically() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        UUID world = UUID.randomUUID();
        UUID issuer = UUID.randomUUID();
        GuardOrder patrol = new GuardOrder(guard.id(), GuardOrder.Type.PATROL,
                List.of(point(world, 1, 0, 1), point(world, 2, 0, 2)), issuer, 100L);
        GuardOrder hold = new GuardOrder(guard.id(), GuardOrder.Type.HOLD_POSITION,
                List.of(), issuer, 200L);

        assertTrue(orders.save(patrol).join());
        assertTrue(orders.save(hold).join());
        assertEquals(hold, orders.find(guard.id()).join().orElseThrow());
    }

    @Test
    void refusesToAttachAnOrderToANonGuardNpc() {
        NpcRecord resident = createNpc(NpcType.RESIDENT);
        GuardOrder order = new GuardOrder(resident.id(), GuardOrder.Type.HOLD_POSITION,
                List.of(), UUID.randomUUID(), 123456L);

        assertFalse(orders.save(order).join());
        assertTrue(orders.find(resident.id()).join().isEmpty());
    }

    @Test
    void deletingOrderRemovesIt() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        GuardOrder order = new GuardOrder(guard.id(), GuardOrder.Type.HOLD_POSITION,
                List.of(), UUID.randomUUID(), 123456L);
        assertTrue(orders.save(order).join());
        assertTrue(orders.delete(guard.id()).join());
        assertFalse(orders.delete(guard.id()).join());
        assertTrue(orders.find(guard.id()).join().isEmpty());
    }

    private NpcRecord createNpc(NpcType type) {
        NpcRecord record = NpcRecord.create(type, type.name(), null, "town", "nation", "{}");
        npcs.insert(record).join();
        return record;
    }

    private static GuardOrder.Waypoint point(UUID world, double x, double y, double z) {
        return new GuardOrder.Waypoint(world, x, y, z, 0, 0);
    }
}
