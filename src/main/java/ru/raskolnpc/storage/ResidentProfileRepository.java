package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;
import ru.raskolnpc.npc.resident.model.ResidentProfession;
import ru.raskolnpc.npc.resident.model.ResidentSkillProgress;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence contract for resident-specific data. */
public interface ResidentProfileRepository {
    CompletableFuture<Void> save(ResidentProfile profile);

    CompletableFuture<Optional<ResidentProfile>> findByNpcId(NpcId npcId);

    /** Loads all resident profiles in a bounded number of database queries. */
    CompletableFuture<List<ResidentProfile>> findAll();

    CompletableFuture<ResidentSkillProgress> addSkillExperience(
            NpcId npcId, ResidentProfession profession, int amount);

    /** Changes the active profession without resetting any profession experience. */
    CompletableFuture<Void> changeProfession(NpcId npcId, ResidentProfession profession);

    /** Atomically changes profession and records the world's full-time day. */
    CompletableFuture<Void> changeProfession(NpcId npcId, ResidentProfession profession,
            long worldDay);

    /** Returns the last profession-change day, or -1 when no change was recorded. */
    CompletableFuture<Long> findLastProfessionChangeDay(NpcId npcId);

    CompletableFuture<Boolean> delete(NpcId npcId);
}
