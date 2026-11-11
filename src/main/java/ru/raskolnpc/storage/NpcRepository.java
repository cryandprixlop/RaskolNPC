package ru.raskolnpc.storage;

import ru.raskolnpc.npc.entity.NpcSpawnLocation;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface NpcRepository {

    CompletableFuture<Void> insert(NpcRecord record);

    CompletableFuture<Optional<NpcRecord>> findById(NpcId id);

    CompletableFuture<List<NpcRecord>> findAll();

    CompletableFuture<Boolean> update(NpcRecord record);

    CompletableFuture<Boolean> updateEntityLocationIfBindingMatches(
            NpcId id, UUID expectedEntityUuid, NpcSpawnLocation location);

    CompletableFuture<Boolean> updateState(NpcId id, NpcState state);

    CompletableFuture<Boolean> delete(NpcId id);
}
