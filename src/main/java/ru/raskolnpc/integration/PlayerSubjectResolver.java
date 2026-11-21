package ru.raskolnpc.integration;

import org.bukkit.entity.Player;

import java.util.Optional;

/** Resolves a player's ownership and settlement context for diplomacy checks. */
@FunctionalInterface
public interface PlayerSubjectResolver {
    Optional<RelationshipPolicy.Subject> resolve(Player player);

    static PlayerSubjectResolver none() {
        return player -> Optional.empty();
    }
}
