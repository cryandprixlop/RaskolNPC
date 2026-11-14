package ru.raskolnpc.integration;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationCapabilitiesTest {
    @Test
    void reportsTownyAndTownBankAsAvailableOnlyWhenEnabledAndConfigured() {
        PluginManager manager = mock(PluginManager.class);
        Plugin towny = plugin("0.103.2.0", true);
        when(manager.getPlugin("Towny")).thenReturn(towny);
        when(manager.getPlugin("WorldGuard")).thenReturn(null);

        var snapshot = new IntegrationCapabilities(manager, true).snapshot();
        var byName = snapshot.stream().collect(java.util.stream.Collectors.toMap(
                IntegrationCapabilities.Capability::name, value -> value));

        assertEquals(IntegrationCapabilities.Status.AVAILABLE, byName.get("Towny").status());
        assertEquals(IntegrationCapabilities.Status.AVAILABLE,
                byName.get("Towny town-bank economy").status());
        assertEquals("0.103.2.0", byName.get("Towny").version());
    }

    @Test
    void doesNotClaimWorldGuardSupportJustBecausePluginIsInstalled() {
        PluginManager manager = mock(PluginManager.class);
        Plugin worldGuard = plugin("7.0.14", true);
        when(manager.getPlugin("Towny")).thenReturn(null);
        when(manager.getPlugin("WorldGuard")).thenReturn(worldGuard);

        var snapshot = new IntegrationCapabilities(manager, false).snapshot();
        var worldGuard = snapshot.stream()
                .filter(value -> value.name().equals("WorldGuard region protection"))
                .findFirst().orElseThrow();

        assertEquals(IntegrationCapabilities.Status.DETECTED_UNSUPPORTED, worldGuard.status());
        assertTrue(worldGuard.details().contains("no verified adapter"));
    }

    @Test
    void reportsConfiguredTownyAsUnavailableWhenPluginIsDisabled() {
        PluginManager manager = mock(PluginManager.class);
        Plugin towny = plugin("0.103.2.0", false);
        when(manager.getPlugin("Towny")).thenReturn(towny);
        when(manager.getPlugin("WorldGuard")).thenReturn(null);

        var towny = new IntegrationCapabilities(manager, true).snapshot().getFirst();
        assertEquals(IntegrationCapabilities.Status.DISABLED, towny.status());
    }

    private static Plugin plugin(String version, boolean enabled) {
        Plugin plugin = mock(Plugin.class);
        org.bukkit.plugin.PluginDescriptionFile description =
                new org.bukkit.plugin.PluginDescriptionFile(
                        "TestPlugin", version, "test.Plugin");
        when(plugin.getDescription()).thenReturn(description);
        when(plugin.isEnabled()).thenReturn(enabled);
        return plugin;
    }
}
