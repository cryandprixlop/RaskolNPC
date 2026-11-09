package ru.raskolnpc.npc.entity;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Objects;

public final class NpcEntityListener implements Listener {
    private final NpcEntityManager entityManager;

    public NpcEntityListener(NpcEntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (var entity : event.getChunk().getEntities()) {
            entityManager.trackLoaded(entity);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (var entity : event.getChunk().getEntities()) {
            entityManager.untrack(entity);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        entityManager.untrack(event.getEntity());
    }
}
