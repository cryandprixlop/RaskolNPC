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

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqliteResidentQuestRepositoryTest {
    @TempDir Path temporaryDirectory;
    private DatabaseManager database;
    private SqliteNpcRepository npcs;
    private SqliteResidentProfileRepository profiles;
    private SqliteResidentQuestRepository quests;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("resident-quest-test"));
        database = new DatabaseManager(plugin, "resident-quests.sqlite", 1);
        database.initialize().join();
        npcs = new SqliteNpcRepository(database);
        profiles = new SqliteResidentProfileRepository(database);
        quests = new SqliteResidentQuestRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
    }

    @Test
    void persistsAcceptanceAndPreventsDuplicateActiveAssignment() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();

        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery").join());
        assertFalse(quests.accept(player, resident.id(), "miner_iron_delivery").join());
        assertEquals(1, quests.findActiveForPlayer(player).join().size());
        assertTrue(quests.findActive(player, resident.id(), "miner_iron_delivery").join().isPresent());
    }

    @Test
    void completionIsOneWayAndCannotBeRepeated() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery").join();

        assertTrue(quests.complete(player, resident.id(), "miner_iron_delivery").join());
        assertFalse(quests.complete(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.findActiveForPlayer(player).join().isEmpty());
    }

    @Test
    void submissionCreditsReputationExactlyOnce() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery").join();

        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertFalse(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertFalse(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertEquals(5, quests.getReputation(player).join());
        assertTrue(quests.findActiveForPlayer(player).join().isEmpty());
    }

    @Test
    void repeatingCompletedQuestAllowsNewSubmissionAndCreditsReputationAgain() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();

        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertEquals(5, quests.getReputation(player).join());

        assertTrue(quests.accept(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.completeSubmission(player, resident.id(), "miner_iron_delivery", 5).join());
        assertEquals(10, quests.getReputation(player).join());
    }

    @Test
    void releasingReservationAllowsRetryWithoutCreditingReputation() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery").join();

        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.releaseSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertEquals(0, quests.getReputation(player).join());
    }

    @Test
    void uncertainSubmissionCanBeMarkedForReview() {
        NpcRecord resident = createResident();
        UUID player = UUID.randomUUID();
        quests.accept(player, resident.id(), "miner_iron_delivery").join();
        assertTrue(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery").join());

        assertTrue(quests.markSubmissionForReview(player, resident.id(),
                "miner_iron_delivery", "test failure").join());
        assertFalse(quests.reserveSubmission(player, resident.id(), "miner_iron_delivery").join());
        assertEquals(0, quests.getReputation(player).join());
    }

    private NpcRecord createResident() {
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Miner",
                null, "town", "nation", "{}");
        npcs.insert(resident).join();
        profiles.save(new ResidentProfile(resident.id(), 30, ResidentProfession.MINER,
                Set.of(), 75, 75, 75, 75, 75, 75, java.util.Map.of())).join();
        return resident;
    }
}
