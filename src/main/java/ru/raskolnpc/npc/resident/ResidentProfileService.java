package ru.raskolnpc.npc.resident;

import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSkillLevel;
import ru.raskolnpc.npc.resident.model.ResidentSkillProgress;
import ru.raskolnpc.storage.ResidentProfileRepository;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates and restores persistent profiles for resident NPCs.
 * All persistence is delegated to the asynchronous repository.
 */
public final class ResidentProfileService {
    private static final int INITIAL_NEED_VALUE = 75;
    private static final ResidentProfession[] PROFESSIONS = ResidentProfession.values();
    private static final ResidentProfile.ResidentTrait[] TRAITS =
            ResidentProfile.ResidentTrait.values();

    private final ResidentProfileRepository repository;

    public ResidentProfileService(ResidentProfileRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
    }

    /** Loads a resident profile asynchronously for interaction and dialogue systems. */
    public CompletableFuture<java.util.Optional<ResidentProfile>> find(
            ru.raskolnpc.npc.model.NpcId npcId) {
        return repository.findByNpcId(java.util.Objects.requireNonNull(npcId, "npcId"));
    }

    /** Awards profession XP using one atomic repository transaction. */
    public CompletableFuture<ResidentSkillProgress> addSkillExperience(
            ru.raskolnpc.npc.model.NpcId npcId, ResidentProfession profession, int amount) {
        return repository.addSkillExperience(npcId, profession, amount);
    }

    /**
     * Changes the resident's active profession. Skill experience for every
     * profession is retained; returning to a former profession restores its
     * previous mastery progress.
     */
    public CompletableFuture<ResidentProfile> changeProfession(
            ru.raskolnpc.npc.model.NpcId npcId, ResidentProfession profession) {
        return changeProfession(npcId, profession, 0L);
    }

    /** Changes profession and persists the cooldown origin in the same repository transaction. */
    public CompletableFuture<ResidentProfile> changeProfession(
            ru.raskolnpc.npc.model.NpcId npcId, ResidentProfession profession, long worldDay) {
        java.util.Objects.requireNonNull(npcId, "npcId");
        java.util.Objects.requireNonNull(profession, "profession");
        if (worldDay < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Игровой день не может быть отрицательным."));
        }
        return repository.findByNpcId(npcId).thenCompose(existing -> {
            if (existing.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Профиль жителя не найден."));
            }
            if (existing.get().profession() == profession) {
                return CompletableFuture.completedFuture(existing.get());
            }
            return repository.changeProfession(npcId, profession, worldDay)
                    .thenCompose(ignored -> repository.findByNpcId(npcId))
                    .thenApply(updated -> updated.orElseThrow(() ->
                            new IllegalStateException("Профиль жителя исчез после смены профессии.")));
        });
    }

    public CompletableFuture<Long> lastProfessionChangeDay(
            ru.raskolnpc.npc.model.NpcId npcId) {
        return repository.findLastProfessionChangeDay(
                java.util.Objects.requireNonNull(npcId, "npcId"));
    }

    /** Creates a profile only when one does not already exist. */
    public CompletableFuture<ResidentProfile> createIfMissing(NpcRecord npc) {
        if (npc == null || npc.type() != NpcType.RESIDENT) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("A resident NPC record is required."));
        }

        return repository.findByNpcId(npc.id()).thenCompose(existing -> {
            if (existing.isPresent()) {
                return CompletableFuture.completedFuture(existing.get());
            }
            ResidentProfile profile = createDefault(npc);
            return repository.save(profile).thenApply(ignored -> profile);
        });
    }

    /**
     * Ensures every resident loaded from storage has a profile.
     * Existing profiles are never overwritten.
     */
    public CompletableFuture<Void> ensureProfiles(List<NpcRecord> records) {
        CompletableFuture<?>[] operations = records.stream()
                .filter(record -> record.type() == NpcType.RESIDENT)
                .map(this::createIfMissing)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(operations);
    }

    private ResidentProfile createDefault(NpcRecord npc) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        ResidentProfession profession = PROFESSIONS[random.nextInt(PROFESSIONS.length)];
        EnumSet<ResidentProfile.ResidentTrait> traits =
                EnumSet.noneOf(ResidentProfile.ResidentTrait.class);
        traits.add(TRAITS[random.nextInt(TRAITS.length)]);
        if (random.nextBoolean()) {
            ResidentProfile.ResidentTrait second;
            do {
                second = TRAITS[random.nextInt(TRAITS.length)];
            } while (traits.contains(second));
            traits.add(second);
        }
        EnumMap<ResidentProfession, ResidentSkillLevel> skills =
                new EnumMap<>(ResidentProfession.class);
        skills.put(profession, ResidentSkillLevel.APPRENTICE);

        return new ResidentProfile(npc.id(), random.nextInt(18, 66), profession,
                traits, INITIAL_NEED_VALUE, INITIAL_NEED_VALUE, INITIAL_NEED_VALUE,
                INITIAL_NEED_VALUE, INITIAL_NEED_VALUE, INITIAL_NEED_VALUE, skills);
    }
}
