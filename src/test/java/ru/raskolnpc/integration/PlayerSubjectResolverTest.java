package ru.raskolnpc.integration;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerSubjectResolverTest {
    @Test
    void noneResolverAlwaysReturnsEmptyWithoutInferringHostility() {
        Player player = mock(Player.class);

        assertTrue(PlayerSubjectResolver.none().resolve(player).isEmpty());
        assertTrue(PlayerSubjectResolver.none().resolve(null).isEmpty());
    }
}
