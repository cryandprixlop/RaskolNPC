package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.guard.model.GuardCondition;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteGuardConditionRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository npcs;
    private SqliteGuardConditionRepository conditions;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("guard-condition-test"));
        database = new DatabaseManager(plugin, "guard-conditions.sqlite", 1);
        database.initialize().join();
        npcs = new SqliteNpcRepository(database);
        conditions = new SqliteGuardConditionRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void savesAndRestoresGuardCondition() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        GuardCondition condition = new GuardCondition(guard.id(), 42, 1000, 2000);

        conditions.save(condition).join();

        assertEquals(java.util.List.of(condition), conditions.findAll().join());
    }

    @Test
    void persistsAndRestoresInjuryState() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        GuardCondition injured = new GuardCondition(guard.id(), 65, 1000, 2000, 2, 1500);
        conditions.save(injured).join();
        assertEquals(injured, conditions.findAll().join().getFirst());
    }

    @Test
    void ignoresNonGuardRowsAndOlderWrites() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        NpcRecord resident = createNpc(NpcType.RESIDENT);
        conditions.save(new GuardCondition(guard.id(), 40, 1000, 2000)).join();
        conditions.save(new GuardCondition(guard.id(), 70, 1000, 1500)).join();
        conditions.save(new GuardCondition(resident.id(), 10, 1000, 3000)).join();

        assertEquals(40, conditions.findAll().join().getFirst().morale());
        assertEquals(1, conditions.findAll().join().size());
    }

    @Test
    void deletingGuardCascadesItsCondition() {
        NpcRecord guard = createNpc(NpcType.GUARD);
        conditions.save(new GuardCondition(guard.id(), 15, 1000, 2000)).join();

        assertTrue(npcs.delete(guard.id()).join());
        assertTrue(conditions.findAll().join().isEmpty());
    }

    private NpcRecord createNpc(NpcType type) {
        NpcRecord record = NpcRecord.create(type, type.name(), null, "town", "nation", "{}");
        npcs.insert(record).join();
        return record;
    }
}
