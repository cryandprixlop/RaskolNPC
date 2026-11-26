package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcId;
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
    void professionExperienceLevelsUpAndSurvivesProfileSaveAndReload() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        ResidentProfile profile = new ResidentProfile(npc.id(), 31,
                ResidentProfession.FARMER, java.util.Set.of(),
                70, 70, 70, 70, 70, 70,
                java.util.Map.of(ResidentProfession.FARMER, ResidentSkillLevel.APPRENTICE));
        repository.save(profile).join();

        var progress = repository.addSkillExperience(
                npc.id(), ResidentProfession.FARMER, 300).join();
        assertEquals(ResidentSkillLevel.SPECIALIST, progress.level());
        assertEquals(300, progress.totalExperience());
        assertEquals(0, progress.experienceIntoLevel());
        assertEquals(400, progress.experienceToNextLevel());

        repository.save(profile).join();
        var loaded = repository.findByNpcId(npc.id()).join().orElseThrow();
        assertEquals(ResidentSkillLevel.SPECIALIST, loaded.currentSkillLevel());
    }

    @Test
    void experienceAccumulatesAndRejectsMissingProfilesAndInvalidAmounts() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Oren", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        repository.save(new ResidentProfile(npc.id(), 25, ResidentProfession.MINER,
                java.util.Set.of(), 75, 75, 75, 75, 75, 75, java.util.Map.of())).join();

        assertEquals(100, repository.addSkillExperience(
                npc.id(), ResidentProfession.MINER, 100).join().totalExperience());
        assertEquals(150, repository.addSkillExperience(
                npc.id(), ResidentProfession.MINER, 50).join().totalExperience());
        assertThrows(IllegalArgumentException.class, () ->
                repository.addSkillExperience(npc.id(), ResidentProfession.MINER, 0));
        assertTrue(repository.addSkillExperience(
                NpcId.fromString("00000000-0000-0000-0000-000000000001"),
                ResidentProfession.MINER, 10).handle((progress, error) -> error != null).join());
    }

    @Test
    void profileAndSkillProgressSurviveDatabaseManagerRestart() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Restarted Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        ResidentProfile profile = new ResidentProfile(npc.id(), 29,
                ResidentProfession.FARMER,
                EnumSet.of(ResidentProfile.ResidentTrait.FRIENDLY),
                61, 72, 83, 44, 95, 77,
                java.util.Map.of(ResidentProfession.FARMER, ResidentSkillLevel.APPRENTICE));
        repository.save(profile).join();
        repository.addSkillExperience(npc.id(), ResidentProfession.FARMER, 300).join();

        database.close();
        var plugin = mock(JavaPlugin.class);
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        org.mockito.Mockito.when(plugin.getLogger()).thenReturn(
                Logger.getLogger("RaskolNPC-resident-profile-restart-test"));
        database = new DatabaseManager(plugin, "resident-tests.sqlite", 1);
        database.initialize().join();
        npcRepository = new SqliteNpcRepository(database);
        repository = new SqliteResidentProfileRepository(database);

        ResidentProfile restored = repository.findByNpcId(npc.id()).join().orElseThrow();
        assertEquals(profile.age(), restored.age());
        assertEquals(profile.traits(), restored.traits());
        assertEquals(profile.hunger(), restored.hunger());
        assertEquals(ResidentSkillLevel.SPECIALIST, restored.currentSkillLevel());
        int experience = database.query(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT experience FROM resident_skills WHERE npc_id = ? AND profession = ?")) {
                query.setString(1, npc.id().toString());
                query.setString(2, ResidentProfession.FARMER.name());
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    return result.getInt(1);
                }
            }
        }).join();
        assertEquals(300, experience);
    }

    @Test
    void changingProfessionPreservesExperienceInBothOldAndNewProfessions() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        repository.save(new ResidentProfile(npc.id(), 31, ResidentProfession.FARMER,
                java.util.Set.of(), 70, 70, 70, 70, 70, 70, java.util.Map.of())).join();

        repository.addSkillExperience(npc.id(), ResidentProfession.FARMER, 700).join();
        repository.changeProfession(npc.id(), ResidentProfession.BLACKSMITH).join();

        var switched = repository.findByNpcId(npc.id()).join().orElseThrow();
        assertEquals(ResidentProfession.BLACKSMITH, switched.profession());
        assertEquals(ResidentSkillLevel.APPRENTICE, switched.currentSkillLevel());

        repository.addSkillExperience(npc.id(), ResidentProfession.BLACKSMITH, 100).join();
        repository.changeProfession(npc.id(), ResidentProfession.FARMER).join();

        var returned = repository.findByNpcId(npc.id()).join().orElseThrow();
        assertEquals(ResidentProfession.FARMER, returned.profession());
        assertEquals(ResidentSkillLevel.EXPERT, returned.currentSkillLevel());
        assertEquals(ResidentSkillLevel.NOVICE,
                returned.skills().get(ResidentProfession.BLACKSMITH));
        int farmerExperience = database.query(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT experience FROM resident_skills WHERE npc_id = ? AND profession = ?")) {
                query.setString(1, npc.id().toString());
                query.setString(2, ResidentProfession.FARMER.name());
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    return result.getInt(1);
                }
            }
        }).join();
        assertEquals(700, farmerExperience);
    }

    @Test
    void professionChangeDayIsPersistedAndUpdatedAtomicallyWithProfession() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        repository.save(new ResidentProfile(npc.id(), 31, ResidentProfession.FARMER,
                java.util.Set.of(), 70, 70, 70, 70, 70, 70, java.util.Map.of())).join();

        assertEquals(-1L, repository.findLastProfessionChangeDay(npc.id()).join());

        repository.changeProfession(npc.id(), ResidentProfession.BLACKSMITH, 42L).join();

        assertEquals(42L, repository.findLastProfessionChangeDay(npc.id()).join());
        assertEquals(ResidentProfession.BLACKSMITH,
                repository.findByNpcId(npc.id()).join().orElseThrow().profession());

        repository.changeProfession(npc.id(), ResidentProfession.FARMER, 57L).join();
        assertEquals(57L, repository.findLastProfessionChangeDay(npc.id()).join());
    }

    @Test
    void rejectsNegativeProfessionChangeDayWithoutChangingProfile() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        repository.save(new ResidentProfile(npc.id(), 31, ResidentProfession.FARMER,
                java.util.Set.of(), 70, 70, 70, 70, 70, 70, java.util.Map.of())).join();

        assertThrows(IllegalArgumentException.class, () ->
                repository.changeProfession(npc.id(), ResidentProfession.BLACKSMITH, -1L));

        assertEquals(ResidentProfession.FARMER,
                repository.findByNpcId(npc.id()).join().orElseThrow().profession());
        assertEquals(-1L, repository.findLastProfessionChangeDay(npc.id()).join());
    }

    @Test
    void professionRequestCooldownIsPersistedAndAllowsOneRequestPerSevenDays() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        repository.save(new ResidentProfile(npc.id(), 31, ResidentProfession.FARMER,
                java.util.Set.of(), 70, 70, 70, 70, 70, 70, java.util.Map.of())).join();

        assertEquals(-1L, repository.findLastProfessionRequestDay(npc.id()).join());
        assertTrue(repository.recordProfessionRequest(npc.id(), 10L).join());
        assertFalse(repository.recordProfessionRequest(npc.id(), 16L).join());
        assertEquals(10L, repository.findLastProfessionRequestDay(npc.id()).join());
        assertTrue(repository.recordProfessionRequest(npc.id(), 17L).join());
        assertEquals(17L, repository.findLastProfessionRequestDay(npc.id()).join());
    }

    @Test
    void concurrentProfessionRequestAttemptsOnlyPersistOneRequest() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        repository.save(new ResidentProfile(npc.id(), 31, ResidentProfession.FARMER,
                java.util.Set.of(), 70, 70, 70, 70, 70, 70, java.util.Map.of())).join();

        var attempts = java.util.stream.IntStream.range(0, 16)
                .mapToObj(ignored -> repository.recordProfessionRequest(npc.id(), 42L))
                .toList();

        long accepted = attempts.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .filter(Boolean.TRUE::equals)
                .count();

        assertEquals(1L, accepted,
                "Only one concurrent interaction may start the seven-day request cooldown.");
        assertEquals(42L, repository.findLastProfessionRequestDay(npc.id()).join());
    }

    @Test
    void professionRequestCooldownSurvivesDatabaseRestart() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        repository.save(new ResidentProfile(npc.id(), 31, ResidentProfession.FARMER,
                java.util.Set.of(), 70, 70, 70, 70, 70, 70, java.util.Map.of())).join();
        assertTrue(repository.recordProfessionRequest(npc.id(), 25L).join());

        database.close();
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RaskolNPC-profession-request-restart-test"));
        database = new DatabaseManager(plugin, "resident-tests.sqlite", 1);
        database.initialize().join();
        repository = new SqliteResidentProfileRepository(database);

        assertEquals(25L, repository.findLastProfessionRequestDay(npc.id()).join());
        assertFalse(repository.recordProfessionRequest(npc.id(), 31L).join());
    }

    @Test
    void changingProfessionRejectsMissingResidentProfile() {
        NpcId missing = NpcId.fromString("00000000-0000-0000-0000-000000000002");
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> repository.changeProfession(missing, ResidentProfession.FARMER).join());
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
