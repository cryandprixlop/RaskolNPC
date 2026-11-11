package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteNpcRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository repository;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RaskolNPC-npc-repository-test"));
        database = new DatabaseManager(plugin, "npc-tests.sqlite", 1);
        database.initialize().join();
        repository = new SqliteNpcRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void persistsSpawnLocationBeforeEntityBindingExists() {
        NpcSpawnLocation spawnLocation = new NpcSpawnLocation(
                UUID.randomUUID(), 12.5, 72.0, -8.25, 135.0f, 10.0f);
        NpcRecord creating = NpcRecord.create(NpcType.GUARD, "Interrupted guard",
                UUID.randomUUID(), "town-1", "nation-1", "{\"level\":1}", spawnLocation);

        assertNull(creating.entityUuid());
        repository.insert(creating).join();

        NpcRecord restored = repository.findById(creating.id()).join().orElseThrow();
        assertNull(restored.entityUuid());
        assertEquals(spawnLocation, restored.spawnLocation());
        assertEquals(creating.id(), restored.id());
    }
}
