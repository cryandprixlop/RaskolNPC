package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence contract for resident-specific data. */
public interface ResidentProfileRepository {
    CompletableFuture<Void> save(ResidentProfile profile);

    CompletableFuture<Optional<ResidentProfile>> findByNpcId(NpcId npcId);

    /** Loads all resident profiles in a bounded number of database queries. */
    CompletableFuture<List<ResidentProfile>> findAll();

    CompletableFuture<Boolean> delete(NpcId npcId);
}
