package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteResidentProfileRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository npcRepository;
    private SqliteResidentProfileRepository repository;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RaskolNPC-resident-profile-test"));
        database = new DatabaseManager(plugin, "resident-tests.sqlite", 1);
        database.initialize().join();
        npcRepository = new SqliteNpcRepository(database);
        repository = new SqliteResidentProfileRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void savesLoadsUpdatesAndDeletesResidentProfile() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();

        EnumMap<ResidentProfession, ResidentSkillLevel> skills =
                new EnumMap<>(ResidentProfession.class);
        skills.put(ResidentProfession.FARMER, ResidentSkillLevel.SPECIALIST);
        skills.put(ResidentProfession.MINER, ResidentSkillLevel.NOVICE);
        ResidentProfile profile = new ResidentProfile(npc.id(), 31,
                ResidentProfession.FARMER,
                EnumSet.of(ResidentProfile.ResidentTrait.FRIENDLY,
                        ResidentProfile.ResidentTrait.HARD_WORKING),
                72, 65, 80, 45, 90, 76, skills);

        repository.save(profile).join();
        assertEquals(Optional.of(profile), repository.findByNpcId(npc.id()).join());

        ResidentProfile updated = new ResidentProfile(npc.id(), 31,
                ResidentProfession.MINER, profile.traits(),
                60, 55, 70, 50, 90, 68, profile.skills());
        repository.save(updated).join();
        assertEquals(Optional.of(updated), repository.findByNpcId(npc.id()).join());
        assertTrue(repository.delete(npc.id()).join());
        assertTrue(repository.findByNpcId(npc.id()).join().isEmpty());
    }

    @Test
    void bulkLoadsProfilesWithTraitsAndSkills() {
        NpcRecord firstNpc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        NpcRecord secondNpc = NpcRecord.create(NpcType.RESIDENT, "Oren", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(firstNpc).join();
        npcRepository.insert(secondNpc).join();

        ResidentProfile first = new ResidentProfile(firstNpc.id(), 24,
                ResidentProfession.FARMER,
                EnumSet.of(ResidentProfile.ResidentTrait.FRIENDLY),
                70, 80, 90, 50, 85, 75,
                java.util.Map.of(ResidentProfession.FARMER, ResidentSkillLevel.EXPERT));
        ResidentProfile second = new ResidentProfile(secondNpc.id(), 41,
                ResidentProfession.BLACKSMITH,
                EnumSet.of(ResidentProfile.ResidentTrait.HARD_WORKING),
                55, 60, 65, 45, 80, 70,
                java.util.Map.of(ResidentProfession.BLACKSMITH, ResidentSkillLevel.MASTER));
        repository.save(first).join();
        repository.save(second).join();

        var loaded = repository.findAll().join();

        assertEquals(2, loaded.size());
        assertTrue(loaded.contains(first));
        assertTrue(loaded.contains(second));
    }

    @Test
    void refusesToSaveProfileForNonResidentNpc() {
        NpcRecord npc = NpcRecord.create(NpcType.GUARD, "Guard", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();

        ResidentProfile profile = new ResidentProfile(npc.id(), 31,
                ResidentProfession.FARMER, java.util.Set.of(),
                50, 50, 50, 50, 50, 50, java.util.Map.of());

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> repository.save(profile).join());
    }
}
