package ru.raskolnpc.storage;

import ru.raskolnpc.guard.model.GuardOrder;
import ru.raskolnpc.npc.model.NpcId;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface GuardOrderRepository {
    CompletableFuture<Boolean> save(GuardOrder order);
    CompletableFuture<Optional<GuardOrder>> find(NpcId npcId);
    CompletableFuture<List<GuardOrder>> findAll();
    CompletableFuture<Boolean> delete(NpcId npcId);
}
