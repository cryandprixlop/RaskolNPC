package ru.raskolnpc.integration;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.List;
import java.util.Objects;

/**
 * Reports what this build can actually use. Detection is deliberately separate
 * from adapter support: a plugin being installed does not mean RaskolNPC has
 * a compatible adapter for it.
 */
public final class IntegrationCapabilities {
    private final PluginManager plugins;
    private final boolean townyConfigured;
    private final boolean worldGuardAdapterAvailable;

    public IntegrationCapabilities(PluginManager plugins, boolean townyConfigured) {
        this(plugins, townyConfigured, true);
    }

    public IntegrationCapabilities(PluginManager plugins, boolean townyConfigured,
                                   boolean worldGuardAdapterAvailable) {
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.townyConfigured = townyConfigured;
        this.worldGuardAdapterAvailable = worldGuardAdapterAvailable;
    }

    public List<Capability> snapshot() {
        Plugin towny = plugins.getPlugin("Towny");
        boolean townyAvailable = townyConfigured && towny != null && towny.isEnabled();

        Plugin worldGuard = plugins.getPlugin("WorldGuard");
        boolean worldGuardPresent = worldGuard != null && worldGuard.isEnabled();
        Plugin siegeWar = plugins.getPlugin("SiegeWar");
        boolean siegeWarPresent = siegeWar != null && siegeWar.isEnabled();

        return List.of(
                new Capability("Towny", townyAvailable ? Status.AVAILABLE
                        : townyPresent(towny) ? Status.DISABLED : Status.NOT_INSTALLED,
                        towny == null ? null : towny.getDescription().getVersion(),
                        townyAvailable ? "Town/Nation authority and guard bank adapter enabled."
                                : "Towny adapter is unavailable or disabled in config."),
                new Capability("Towny town-bank economy", townyAvailable ? Status.AVAILABLE
                        : townyPresent(towny) ? Status.DISABLED : Status.NOT_INSTALLED,
                        towny == null ? null : towny.getDescription().getVersion(),
                        townyAvailable ? "Guard hiring and daily rent use Towny town accounts."
                                : "No supported town-bank economy provider is active."),
                new Capability("WorldGuard region protection",
                        worldGuardPresent ? worldGuardAdapterAvailable ? Status.AVAILABLE : Status.DETECTED_UNSUPPORTED
                                : worldGuard != null ? Status.DISABLED : Status.NOT_CONFIGURED,
                        worldGuard == null ? null : worldGuard.getDescription().getVersion(),
                        worldGuardPresent
                                ? worldGuardPresent && worldGuardAdapterAvailable
                                ? "WorldGuard API adapter can query the MOB_DAMAGE flag; guard combat enforcement is not yet wired."
                                : worldGuardPresent
                                ? "WorldGuard is detected, but its API adapter could not be initialized."
                                : "No region-protection adapter is configured."
                                : "No region-protection adapter is configured."),
                new Capability("SiegeWar diplomacy",
                        siegeWarPresent ? Status.AVAILABLE
                                : siegeWar != null ? Status.DISABLED : Status.NOT_CONFIGURED,
                        siegeWar == null ? null : siegeWar.getDescription().getVersion(),
                        siegeWarPresent
                                ? "Verified SiegeWar API adapter can identify active attacker/defender nation pairs."
                                : "SiegeWar is not enabled; unknown relations remain neutral.")
        );
    }

    private static boolean townyPresent(Plugin towny) {
        return towny != null;
    }

    public enum Status {
        AVAILABLE,
        DISABLED,
        NOT_INSTALLED,
        DETECTED_UNSUPPORTED,
        NOT_CONFIGURED
    }

    public record Capability(String name, Status status, String version, String details) { }
}
