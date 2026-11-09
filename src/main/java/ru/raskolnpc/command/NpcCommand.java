package ru.raskolnpc.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.model.NpcType;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

public final class NpcCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "raskolnpc.admin";

    private final Plugin plugin;
    private final NpcCommandService service;
    private final NpcCommandMessages messages;

    public NpcCommand(Plugin plugin, NpcCommandService service,
                      NpcCommandMessages messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "no-permission", "&cУ вас нет прав на эту команду.");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "delete" -> delete(sender, args);
            default -> help(sender, label);
        }
        return true;
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only", "&cЭту команду может выполнить только игрок.");
            return;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("resident")) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc create resident <имя>");
            return;
        }
        String name = NpcCommandParser.joinName(args, 2);
        if (name.isBlank() || name.length() > 64) {
            messages.sendRaw(sender, "&cИмя должно содержать от 1 до 64 символов.");
            return;
        }

        service.createResident(name, player).whenComplete((record, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    if (error != null) {
                        messages.send(sender, "operation-failed",
                                "&cНе удалось создать NPC: " + rootMessage(error));
                        return;
                    }
                    messages.sendRaw(sender, "&aЖитель создан. UUID: &f" + record.id());
                }));
    }

    private void list(CommandSender sender) {
        List<NpcRecord> records = service.list();
        messages.sendRaw(sender, "&6NPC в реестре: &f" + records.size());
        for (NpcRecord record : records) {
            messages.sendRaw(sender, "&e" + record.type() + " &f" + record.name()
                    + " &7(" + record.id() + ") &8— " + record.state());
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc info <UUID>");
            return;
        }
        var id = NpcCommandParser.parseId(args[1]);
        if (id.isEmpty()) {
            messages.sendRaw(sender, "&cНекорректный UUID.");
            return;
        }
        service.find(id.get()).whenComplete((record, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    if (error != null || record.isEmpty()) {
                        messages.sendRaw(sender, "&cNPC не найден.");
                        return;
                    }
                    NpcRecord npc = record.get();
                    messages.sendRaw(sender, "&6NPC: &f" + npc.name());
                    messages.sendRaw(sender, "&7Тип: &f" + npc.type()
                            + " &7Состояние: &f" + npc.state());
                    messages.sendRaw(sender, "&7UUID: &f" + npc.id());
                    messages.sendRaw(sender, "&7Entity UUID: &f" + npc.entityUuid());
                }));
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc delete <UUID>");
            return;
        }
        var id = NpcCommandParser.parseId(args[1]);
        if (id.isEmpty()) {
            messages.sendRaw(sender, "&cНекорректный UUID.");
            return;
        }
        service.delete(id.get()).whenComplete((deleted, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    if (error != null) {
                        messages.sendRaw(sender, "&cОшибка удаления: " + rootMessage(error));
                    } else if (Boolean.TRUE.equals(deleted)) {
                        messages.sendRaw(sender, "&aNPC удалён.");
                    } else {
                        messages.sendRaw(sender, "&cNPC не найден.");
                    }
                }));
    }

    private void help(CommandSender sender, String label) {
        messages.sendRaw(sender, "&6Команды RaskolNPC:");
        messages.sendRaw(sender, "&e/" + label + " create resident <имя> &7— создать жителя");
        messages.sendRaw(sender, "&e/" + label + " list &7— список NPC");
        messages.sendRaw(sender, "&e/" + label + " info <UUID> &7— информация");
        messages.sendRaw(sender, "&e/" + label + " delete <UUID> &7— удалить NPC");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) return startsWith(List.of("create", "list", "info", "delete"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return startsWith(List.of("resident"), args[1]);
        }
        return List.of();
    }

    private List<String> startsWith(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current.getCause() != null)
                && current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
