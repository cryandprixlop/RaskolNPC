package ru.raskolnpc.storage;

import ru.raskolnpc.npc.model.NpcId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface GuardEquipmentRepository {
    CompletableFuture<Void> save(NpcId npcId, byte[] equipment);
    CompletableFuture<Optional<byte[]>> find(NpcId npcId);
}
