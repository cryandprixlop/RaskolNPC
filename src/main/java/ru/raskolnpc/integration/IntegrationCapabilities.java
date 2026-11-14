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

    public IntegrationCapabilities(PluginManager plugins, boolean townyConfigured) {
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.townyConfigured = townyConfigured;
    }

    public List<Capability> snapshot() {
        Plugin towny = plugins.getPlugin("Towny");
        boolean townyAvailable = townyConfigured && towny != null && towny.isEnabled();

        Plugin worldGuard = plugins.getPlugin("WorldGuard");
        boolean worldGuardPresent = worldGuard != null && worldGuard.isEnabled();

        return List.of(
                new Capability("Towny", townyAvailable ? Status.AVAILABLE
                        : townyPresent(towny) ? Status.DISABLED : Status.NOT_INSTALLED,
                        towny == null ? null : towny.getDescription().getVersion(),
                        townyAvailable ? "Town/Nation authority and guard bank adapter enabled."
                                : "Towny adapter is unavailable or disabled in config."),
                new Capability("Towny town-bank economy", townyAvailable ? Status.AVAILABLE
                        : Status.NOT_INSTALLED,
                        towny == null ? null : towny.getDescription().getVersion(),
                        townyAvailable ? "Guard hiring and daily rent use Towny town accounts."
                                : "No supported town-bank economy provider is active."),
                new Capability("WorldGuard region protection",
                        worldGuardPresent ? Status.DETECTED_UNSUPPORTED : Status.NOT_CONFIGURED,
                        worldGuard == null ? null : worldGuard.getDescription().getVersion(),
                        worldGuardPresent
                                ? "WorldGuard is installed, but this build has no verified adapter; no protection is claimed."
                                : "No region-protection adapter is configured.")
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
