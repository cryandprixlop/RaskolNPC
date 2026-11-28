package ru.raskolnpc.storage;

import ru.raskolnpc.guard.model.GuardSquad;
import ru.raskolnpc.npc.model.NpcId;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface GuardSquadRepository {
    CompletableFuture<Boolean> create(UUID id, String name, UUID creator, long createdAt);
    CompletableFuture<Boolean> addMember(UUID squadId, NpcId guardId, int maximumMembers);
    CompletableFuture<Boolean> removeMember(NpcId guardId);
    CompletableFuture<Boolean> delete(UUID squadId);
    CompletableFuture<List<GuardSquad>> findAll();
}