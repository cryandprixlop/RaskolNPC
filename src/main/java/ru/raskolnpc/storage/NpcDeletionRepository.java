package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface NpcDeletionRepository {
    CompletableFuture<Boolean> deleteAndQueue(NpcId id);
    CompletableFuture<List<NpcId>> findPending();
    CompletableFuture<Boolean> complete(NpcId id);
}
