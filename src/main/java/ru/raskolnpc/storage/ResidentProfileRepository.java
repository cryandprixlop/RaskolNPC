package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.model.ResidentProfile;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Asynchronous persistence contract for resident-specific data. */
public interface ResidentProfileRepository {
    CompletableFuture<Void> save(ResidentProfile profile);

    CompletableFuture<Optional<ResidentProfile>> findByNpcId(NpcId npcId);

    CompletableFuture<Boolean> delete(NpcId npcId);
}
