package ru.raskolnpc.storage;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.raskolnpc.core.database.DatabaseManager;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentProfile;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteResidentScheduleRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository npcRepository;
    private SqliteResidentProfileRepository profileRepository;
    private SqliteResidentScheduleRepository repository;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("RaskolNPC-resident-schedule-test"));
        database = new DatabaseManager(plugin, "resident-schedule-tests.sqlite", 1);
        database.initialize().join();
        npcRepository = new SqliteNpcRepository(database);
        profileRepository = new SqliteResidentProfileRepository(database);
        repository = new SqliteResidentScheduleRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void savesAndReplacesScheduleAndDestinations() {
        NpcRecord npc = createResident("Mira");
        ResidentSchedule first = new ResidentSchedule(List.of(
                new ResidentSchedule.Entry(DayOfWeek.MONDAY, 480, 720,
                        ResidentSchedule.Activity.WORK,
                        ResidentDestination.DestinationType.WORK),
                new ResidentSchedule.Entry(DayOfWeek.MONDAY, 720, 780,
                        ResidentSchedule.Activity.EAT,
                        ResidentDestination.DestinationType.MARKET)));
        repository.saveSchedule(npc.id(), first).join();
        assertEquals(first, repository.findSchedule(npc.id()).join().orElseThrow());

        ResidentSchedule replacement = new ResidentSchedule(List.of(
                new ResidentSchedule.Entry(DayOfWeek.TUESDAY, 540, 600,
                        ResidentSchedule.Activity.LEISURE,
                        ResidentDestination.DestinationType.COMMUNITY)));
        repository.saveSchedule(npc.id(), replacement).join();
        assertEquals(replacement, repository.findSchedule(npc.id()).join().orElseThrow());

        ResidentDestination home = new ResidentDestination(npc.id(),
                ResidentDestination.DestinationType.HOME, UUID.randomUUID(),
                10.5, 64, -4.5, 90, 0, "House");
        repository.saveDestination(home).join();
        assertEquals(List.of(home), repository.findDestinations(npc.id()).join());

        ResidentDestination movedHome = new ResidentDestination(npc.id(),
                ResidentDestination.DestinationType.HOME, home.worldId(),
                20, 65, 8, 180, 0, "New house");
        repository.saveDestination(movedHome).join();
        assertEquals(List.of(movedHome), repository.findDestinations(npc.id()).join());
        assertTrue(repository.deleteDestination(npc.id(),
                ResidentDestination.DestinationType.HOME).join());
        assertTrue(repository.findDestinations(npc.id()).join().isEmpty());
    }

    @Test
    void loadsResidentWithHomeEvenWhenNoScheduleExists() {
        NpcRecord npc = createResident("HomeOnly");
        ResidentDestination home = new ResidentDestination(npc.id(),
                ResidentDestination.DestinationType.HOME, UUID.randomUUID(),
                12, 64, -8, 0, 0, "Home");
        repository.saveDestination(home).join();

        var snapshot = repository.findAllBehaviorData().join().stream()
                .filter(data -> data.npcId().equals(npc.id()))
                .findFirst().orElseThrow();

        assertTrue(snapshot.schedule().entries().isEmpty());
        assertEquals(List.of(home), snapshot.destinations());
    }

    @Test
    void loadsScheduledResidentEvenWhenDestinationIsNotConfiguredYet() {
        NpcRecord npc = createResident("ScheduleOnly");
        ResidentSchedule schedule = new ResidentSchedule(List.of(
                new ResidentSchedule.Entry(DayOfWeek.MONDAY, 480, 600,
                        ResidentSchedule.Activity.WORK,
                        ResidentDestination.DestinationType.WORK)));
        repository.saveSchedule(npc.id(), schedule).join();

        var snapshot = repository.findAllBehaviorData().join().stream()
                .filter(data -> data.npcId().equals(npc.id()))
                .findFirst().orElseThrow();

        assertEquals(schedule, snapshot.schedule());
        assertTrue(snapshot.destinations().isEmpty());
    }

    @Test
    void refusesScheduleAndDestinationForMissingResidentProfile() {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, "Mira", null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        ResidentSchedule schedule = new ResidentSchedule(List.of(
                new ResidentSchedule.Entry(DayOfWeek.MONDAY, 480, 600,
                        ResidentSchedule.Activity.WORK,
                        ResidentDestination.DestinationType.WORK)));

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> repository.saveSchedule(npc.id(), schedule).join());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> repository.saveDestination(new ResidentDestination(npc.id(),
                        ResidentDestination.DestinationType.HOME, UUID.randomUUID(),
                        0, 64, 0, 0, 0, null)).join());
    }

    private NpcRecord createResident(String name) {
        NpcRecord npc = NpcRecord.create(NpcType.RESIDENT, name, null,
                "town-1", "nation-1", "{}");
        npcRepository.insert(npc).join();
        profileRepository.save(new ResidentProfile(npc.id(), 30,
                ResidentProfession.FARMER, java.util.Set.of(),
                75, 75, 75, 75, 75, 75, java.util.Map.of())).join();
        return npc;
    }
}
