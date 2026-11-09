package ru.raskolnpc.npc.entity;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import ru.raskolnpc.npc.lifecycle.NpcRecoveryService;

import java.util.Objects;

public final class NpcEntityListener implements Listener {
    private final NpcEntityManager entityManager;
    private final NpcRecoveryService recoveryService;

    public NpcEntityListener(NpcEntityManager entityManager,
                             NpcRecoveryService recoveryService) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        recoveryService.onEntitiesLoad(event.getChunk(), event.getEntities());
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        recoveryService.onEntitiesUnload(event.getEntities());
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        entityManager.untrack(event.getEntity());
    }
}
