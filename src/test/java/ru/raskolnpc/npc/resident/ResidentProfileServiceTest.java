package ru.raskolnpc.npc.resident;

import org.junit.jupiter.api.Test;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;
import ru.raskolnpc.storage.ResidentProfileRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentProfileServiceTest {

    @Test
    void changesProfessionAndReturnsReloadedProfileWithoutDiscardingSkillHistory() {
        ResidentProfileRepository repository = mock(ResidentProfileRepository.class);
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Resident",
                null, "town-1", null, "{}");
        ResidentProfile before = new ResidentProfile(resident.id(), 42,
                ResidentProfession.FARMER, Set.of(), 60, 70, 80, 50, 75, 65,
                Map.of(ResidentProfession.FARMER, ResidentSkillLevel.EXPERT));
        ResidentProfile after = new ResidentProfile(resident.id(), 42,
                ResidentProfession.BLACKSMITH, Set.of(), 60, 70, 80, 50, 75, 65,
                Map.of(ResidentProfession.FARMER, ResidentSkillLevel.EXPERT,
                        ResidentProfession.BLACKSMITH, ResidentSkillLevel.APPRENTICE));
        when(repository.findByNpcId(resident.id())).thenReturn(
                CompletableFuture.completedFuture(Optional.of(before)),
                CompletableFuture.completedFuture(Optional.of(after)));
        when(repository.changeProfession(resident.id(), ResidentProfession.BLACKSMITH))
                .thenReturn(CompletableFuture.completedFuture(null));

        ResidentProfile changed = new ResidentProfileService(repository)
                .changeProfession(resident.id(), ResidentProfession.BLACKSMITH).join();

        assertEquals(ResidentProfession.BLACKSMITH, changed.profession());
        assertEquals(ResidentSkillLevel.EXPERT, changed.skills().get(ResidentProfession.FARMER));
        assertEquals(ResidentSkillLevel.APPRENTICE, changed.currentSkillLevel());
        verify(repository).changeProfession(resident.id(), ResidentProfession.BLACKSMITH);
    }

    @Test
    void doesNotWriteWhenResidentKeepsCurrentProfession() {
        ResidentProfileRepository repository = mock(ResidentProfileRepository.class);
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Resident",
                null, "town-1", null, "{}");
        ResidentProfile existing = new ResidentProfile(resident.id(), 42,
                ResidentProfession.FARMER, Set.of(), 60, 70, 80, 50, 75, 65, Map.of());
        when(repository.findByNpcId(resident.id()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(existing)));

        ResidentProfile unchanged = new ResidentProfileService(repository)
                .changeProfession(resident.id(), ResidentProfession.FARMER).join();

        assertSame(existing, unchanged);
        verify(repository, never()).changeProfession(any(), any());
    }

    @Test
    void rejectsProfessionChangeWhenProfileDoesNotExist() {
        ResidentProfileRepository repository = mock(ResidentProfileRepository.class);
        var id = ru.raskolnpc.npc.model.NpcId.create();
        when(repository.findByNpcId(id))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        assertThrows(java.util.concurrent.CompletionException.class, () ->
                new ResidentProfileService(repository)
                        .changeProfession(id, ResidentProfession.FARMER).join());
        verify(repository, never()).changeProfession(any(), any());
    }

    @Test
    void createsAndPersistsDefaultProfileForResident() {
        ResidentProfileRepository repository = mock(ResidentProfileRepository.class);
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Resident",
                null, "town-1", null, "{}");
        when(repository.findByNpcId(resident.id()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(repository.save(any(ResidentProfile.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        ResidentProfile profile = new ResidentProfileService(repository)
                .createIfMissing(resident).join();

        assertEquals(resident.id(), profile.npcId());
        assertTrue(profile.age() >= 18 && profile.age() <= 65);
        assertEquals(75, profile.hunger());
        assertEquals(75, profile.energy());
        assertEquals(75, profile.rest());
        assertEquals(75, profile.social());
        assertEquals(75, profile.housingSatisfaction());
        assertEquals(75, profile.mood());
        assertEquals(ResidentSkillLevel.APPRENTICE, profile.currentSkillLevel());
        verify(repository).save(profile);
    }

    @Test
    void preservesExistingProfileWithoutSavingOverIt() {
        ResidentProfileRepository repository = mock(ResidentProfileRepository.class);
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Resident",
                null, "town-1", null, "{}");
        ResidentProfile existing = new ResidentProfile(resident.id(), 42,
                ResidentProfession.FARMER, Set.of(), 30, 40, 50, 60, 70, 80,
                Map.of(ResidentProfession.FARMER, ResidentSkillLevel.EXPERT));
        when(repository.findByNpcId(resident.id()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(existing)));

        ResidentProfile actual = new ResidentProfileService(repository)
                .createIfMissing(resident).join();

        assertSame(existing, actual);
        verify(repository, never()).save(any());
    }

    @Test
    void startupBackfillOnlyCreatesProfilesForResidents() {
        ResidentProfileRepository repository = mock(ResidentProfileRepository.class);
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Resident",
                null, "town-1", null, "{}");
        NpcRecord guard = NpcRecord.create(NpcType.GUARD, "Guard",
                null, "town-1", "nation-1", "{}");
        when(repository.findByNpcId(resident.id()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(repository.save(any(ResidentProfile.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertDoesNotThrow(() -> new ResidentProfileService(repository)
                .ensureProfiles(List.of(resident, guard)).join());

        verify(repository).save(any(ResidentProfile.class));
        verify(repository, never()).findByNpcId(guard.id());
    }

    @Test
    void propagatesPersistenceFailureSoResidentCreationCanBeRolledBack() {
        ResidentProfileRepository repository = mock(ResidentProfileRepository.class);
        NpcRecord resident = NpcRecord.create(NpcType.RESIDENT, "Resident",
                null, "town-1", null, "{}");
        when(repository.findByNpcId(resident.id()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(repository.save(any(ResidentProfile.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("database unavailable")));

        assertThrows(RuntimeException.class, () ->
                new ResidentProfileService(repository).createIfMissing(resident).join());
    }
}
