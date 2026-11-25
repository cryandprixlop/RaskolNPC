package ru.raskolnpc.integration;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import ru.raskolnpc.integration.RelationshipPolicy.Subject;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerSubjectResolverTest {
    @Test
    void noneResolverAlwaysReturnsEmptyForAnOnlinePlayer() {
        Player player = mock(Player.class);

        assertTrue(PlayerSubjectResolver.none().resolve(player).isEmpty());
    }

    @Test
    void noneResolverRejectsNullInputConsistentlyWithTownyResolverContract() {
        assertThrows(NullPointerException.class,
                () -> PlayerSubjectResolver.none().resolve(null));
    }
}
