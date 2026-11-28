package ru.raskolnpc.storage;

import ru.raskolnpc.guard.model.GuardCondition;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface GuardConditionRepository {
    CompletableFuture<List<GuardCondition>> findAll();
    CompletableFuture<Void> save(GuardCondition condition);
}
