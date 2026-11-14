package ru.raskolnpc.npc.resident;

import ru.raskolnpc.command.NpcCommandService;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.model.NpcType;
import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;
import ru.raskolnpc.storage.ResidentScheduleRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Coordinates resident schedule edits, persistence, validation and cache refresh. */
public final class ResidentScheduleCommandService {
    private final NpcCommandService npcService;
    private final ResidentScheduleRepository repository;
    private final ResidentBehaviorService behavior;

    public ResidentScheduleCommandService(NpcCommandService npcService,
            ResidentScheduleRepository repository, ResidentBehaviorService behavior) {
        this.npcService = Objects.requireNonNull(npcService, "npcService");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.behavior = Objects.requireNonNull(behavior, "behavior");
    }

    public CompletableFuture<ResidentSchedule> listSchedule(NpcId id) {
        return requireResident(id).thenCompose(ignored ->
                repository.findSchedule(id).thenApply(value ->
                        value.orElseGet(() -> new ResidentSchedule(List.of()))));
    }

    public CompletableFuture<ResidentSchedule> addSchedule(NpcId id, ResidentSchedule.Entry entry) {
        Objects.requireNonNull(entry, "entry");
        return requireResident(id).thenCompose(ignored -> repository.findSchedule(id))
                .thenCompose(existing -> {
                    List<ResidentSchedule.Entry> entries = new ArrayList<>(
                            existing.map(ResidentSchedule::entries).orElseGet(List::of));
                    entries.add(entry);
                    final ResidentSchedule updated;
                    try { updated = new ResidentSchedule(entries); }
                    catch (IllegalArgumentException conflict) {
                        return CompletableFuture.failedFuture(new IllegalArgumentException(
                                "Интервал пересекается с уже заданным расписанием.", conflict));
                    }
                    return repository.saveSchedule(id, updated).thenApply(ignored -> {
                        behavior.reload();
                        return updated;
                    });
                });
    }

    public CompletableFuture<Void> setDestination(ResidentDestination destination) {
        Objects.requireNonNull(destination, "destination");
        return requireResident(destination.npcId()).thenCompose(ignored ->
                repository.saveDestination(destination)).thenRun(behavior::reload);
    }

    public CompletableFuture<Boolean> deleteDestination(NpcId id, ResidentDestination.DestinationType type) {
        Objects.requireNonNull(type, "type");
        return requireResident(id).thenCompose(ignored -> repository.deleteDestination(id, type))
                .thenApply(deleted -> { if (deleted) behavior.reload(); return deleted; });
    }

    private CompletableFuture<Void> requireResident(NpcId id) {
        return npcService.find(id).thenApply(found -> {
            if (found.isEmpty()) throw new IllegalArgumentException("NPC с таким UUID не найден.");
            if (found.get().type() != NpcType.RESIDENT)
                throw new IllegalArgumentException("Управлять расписанием можно только у NPC-жителя.");
            return null;
        });
    }
}
