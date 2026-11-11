package ru.raskolnpc.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.guard.GuardRentService;
import ru.raskolnpc.guard.model.GuardRentPayment;
import ru.raskolnpc.guard.model.GuardRentPaymentStatus;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

public final class NpcCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "raskolnpc.admin";

    private final Plugin plugin;
    private final NpcCommandService service;
    private final NpcCommandMessages messages;
    private volatile GuardRentService guardRentService;

    public NpcCommand(Plugin plugin, NpcCommandService service,
                      NpcCommandMessages messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    public void setGuardRentService(GuardRentService guardRentService) {
        this.guardRentService = guardRentService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        boolean admin = sender.hasPermission(PERMISSION);
        boolean townResidentCreation = args.length >= 2
                && args[0].equalsIgnoreCase("create")
                && args[1].equalsIgnoreCase("resident")
                && sender instanceof Player
                && service.isTownyIntegrationEnabled();
        boolean guardHiring = args.length >= 2
                && args[0].equalsIgnoreCase("guard")
                && args[1].equalsIgnoreCase("hire")
                && sender instanceof Player
                && service.isGuardHiringEnabled();

        if (!admin && !townResidentCreation && !guardHiring) {
            messages.send(sender, "general.no-permission", "&cУ вас нет прав на эту команду.");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "guard" -> guard(sender, args);
            case "list" -> {
                if (admin) list(sender);
                else deny(sender);
            }
            case "info" -> {
                if (admin) info(sender, args);
                else deny(sender);
            }
            case "delete" -> {
                if (admin) delete(sender, args);
                else deny(sender);
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only", "&cЭту команду может выполнить только игрок.");
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
                        messages.send(sender, "general.operation-failed",
                                "&cНе удалось создать NPC: " + rootMessage(error));
                        return;
                    }
                    messages.sendRaw(sender, "&aЖитель создан. UUID: &f" + record.id());
                }));
    }

    private void guard(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("hire")
                && args.length >= 3 && (args[2].equalsIgnoreCase("review")
                || args[2].equalsIgnoreCase("resolve"))) {
            if (!sender.hasPermission(PERMISSION)) {
                deny(sender);
                return;
            }
            guardHireReview(sender, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("rent")) {
            if (!sender.hasPermission(PERMISSION)) {
                deny(sender);
                return;
            }
            guardRent(sender, args);
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only", "&cЭту команду может выполнить только игрок.");
            return;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("hire")) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard hire <уровень> <имя>");
            return;
        }
        final int level;
        try {
            level = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            messages.sendRaw(sender, "&cУровень должен быть числом от 1 до 10.");
            return;
        }
        String name = NpcCommandParser.joinName(args, 3);
        service.hireGuard(name, level, player).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    if (error != null) {
                        messages.send(sender, "general.operation-failed",
                                "&cНе удалось нанять стража: " + rootMessage(error));
                        return;
                    }
                    messages.sendRaw(sender, "&aСтраж нанят. Уровень: &f" + result.level()
                            + " &7Стоимость: &f" + result.hireCost()
                            + " &7Суточная аренда: &f" + result.dailyRent());
                }));
    }

    private void guardHireReview(CommandSender sender, String[] args) {
        if (args.length == 3 && args[2].equalsIgnoreCase("review")) {
            service.findReviewHiringPayments().whenComplete((payments, error) ->
                    runMain(sender, () -> {
                        if (error != null) {
                            messages.sendRaw(sender, "&cНе удалось получить операции найма: "
                                    + rootMessage(error));
                            return;
                        }
                        if (payments.isEmpty()) {
                            messages.sendRaw(sender, "&aОпераций найма на проверке нет.");
                            return;
                        }
                        messages.sendRaw(sender, "&6Операции найма на проверке: &f" + payments.size());
                        for (var payment : payments) {
                            messages.sendRaw(sender, "&eID: &f" + payment.paymentId()
                                    + " &7Страж: &f" + payment.guardName()
                                    + " &7Город: &f" + payment.townId()
                                    + " &7Сумма: &f" + payment.hireCost()
                                    + " &7Статус: &f" + payment.status());
                            if (payment.failureReason() != null && !payment.failureReason().isBlank()) {
                                messages.sendRaw(sender, "&8Причина: " + payment.failureReason());
                            }
                        }
                    }));
            return;
        }
        if (args.length != 5 || !args[2].equalsIgnoreCase("resolve")
                || !(args[4].equalsIgnoreCase("paid")
                || args[4].equalsIgnoreCase("failed")
                || args[4].equalsIgnoreCase("refunded"))) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard hire review");
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard hire resolve <ID> <paid|failed|refunded>");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&cРазрешение операции доступно только администратору-игроку "
                    + "для сохранения UUID.");
            return;
        }
        String resolution = switch (args[4].toLowerCase(Locale.ROOT)) {
            case "paid" -> "DEBIT_CONFIRMED";
            case "failed" -> "DEBIT_FAILED";
            default -> "REFUNDED";
        };
        service.resolveHiringReview(args[3], resolution, player.getUniqueId())
                .whenComplete((resolved, error) -> runMain(sender, () -> {
                    if (error != null) {
                        messages.sendRaw(sender, "&cОшибка разрешения операции: " + rootMessage(error));
                    } else if (Boolean.TRUE.equals(resolved)) {
                        messages.sendRaw(sender, "&aРезультат операции зафиксирован. "
                                + "Деньги не списывались и не возвращались этой командой.");
                    } else {
                        messages.sendRaw(sender, "&cОперация не найдена или уже разрешена.");
                    }
                }));
    }

    private void guardRent(CommandSender sender, String[] args) {
        GuardRentService rent = guardRentService;
        if (rent == null) {
            messages.sendRaw(sender, "&cСервис аренды недоступен: проверьте интеграцию Towny.");
            return;
        }
        if (args.length == 3 && args[2].equalsIgnoreCase("review")) {
            rent.findReviewPayments().whenComplete((payments, error) ->
                    runMain(sender, () -> {
                        if (error != null) {
                            messages.sendRaw(sender, "&cНе удалось получить спорные платежи: "
                                    + rootMessage(error));
                            return;
                        }
                        if (payments.isEmpty()) {
                            messages.sendRaw(sender, "&aСпорных платежей нет.");
                            return;
                        }
                        messages.sendRaw(sender, "&6Платежи, требующие проверки: &f"
                                + payments.size());
                        for (GuardRentPayment payment : payments) {
                            messages.sendRaw(sender, "&eID: &f" + payment.paymentId()
                                    + " &7NPC: &f" + payment.npcId()
                                    + " &7Город: &f" + payment.townId()
                                    + " &7Сумма: &f" + payment.amount()
                                    + " &7Срок: &f" + payment.rentDueAt());
                            if (payment.failureReason() != null
                                    && !payment.failureReason().isBlank()) {
                                messages.sendRaw(sender, "&8Причина: " + payment.failureReason());
                            }
                        }
                    }));
            return;
        }
        if (args.length != 5 || !args[2].equalsIgnoreCase("resolve")
                || !(args[4].equalsIgnoreCase("paid")
                || args[4].equalsIgnoreCase("failed"))) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard rent review");
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard rent resolve <ID> <paid|failed>");
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&cРазрешение платежа доступно только администратору-игроку, "
                    + "чтобы сохранить его UUID в журнале.");
            return;
        }
        boolean paid = args[4].equalsIgnoreCase("paid");
        String paymentId = args[3];
        rent.resolveReview(paymentId, paid, player.getUniqueId())
                .whenComplete((resolved, error) -> runMain(sender, () -> {
                    if (error != null) {
                        messages.sendRaw(sender, "&cОшибка разрешения платежа: " + rootMessage(error));
                    } else if (Boolean.TRUE.equals(resolved)) {
                        messages.sendRaw(sender, paid
                                ? "&aПлатёж отмечен как списанный. Повторного списания не было."
                                : "&eПлатёж отмечен как неуспешный. Применена задолженность.");
                    } else {
                        messages.sendRaw(sender, "&cПлатёж не найден или уже не требует проверки.");
                    }
                }));
    }

    private void runMain(CommandSender sender, Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            if (Bukkit.isPrimaryThread()) action.run();
            else Bukkit.getScheduler().runTask(plugin, action);
        } catch (RuntimeException error) {
            plugin.getLogger().severe("Could not schedule rent command response: " + error.getMessage());
        }
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

    private void deny(CommandSender sender) {
        messages.send(sender, "general.no-permission", "&cУ вас нет прав на эту команду.");
    }

    private void help(CommandSender sender, String label) {
        messages.sendRaw(sender, "&6Команды RaskolNPC:");
        messages.sendRaw(sender, "&e/" + label + " create resident <имя> &7— создать жителя");
        if (service.isGuardHiringEnabled()) {
            messages.sendRaw(sender, "&e/" + label + " guard hire <уровень> <имя> &7— нанять стража");
        }
        if (sender.hasPermission(PERMISSION)) {
            messages.sendRaw(sender, "&e/" + label + " list &7— список NPC");
            messages.sendRaw(sender, "&e/" + label + " info <UUID> &7— информация");
            messages.sendRaw(sender, "&e/" + label + " delete <UUID> &7— удалить NPC");
            messages.sendRaw(sender, "&e/" + label + " guard rent review &7— спорные платежи");
            messages.sendRaw(sender, "&e/" + label + " guard hire review &7— спорные операции найма");
            messages.sendRaw(sender, "&e/" + label + " guard hire resolve <ID> <paid|failed|refunded> &7— разрешить найм");
            messages.sendRaw(sender, "&e/" + label + " guard rent resolve <ID> <paid|failed> &7— разрешить платёж");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        boolean canCreateTownResident = sender instanceof Player
                && service.isTownyIntegrationEnabled();
        boolean canHireGuard = sender instanceof Player && service.isGuardHiringEnabled();
        if (!sender.hasPermission(PERMISSION) && !canCreateTownResident && !canHireGuard) {
            return List.of();
        }
        if (args.length == 1) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            if (sender.hasPermission(PERMISSION)) values.addAll(List.of("create", "list", "info", "delete"));
            if (canHireGuard || sender.hasPermission(PERMISSION)) values.add("guard");
            if (canCreateTownResident && !values.contains("create")) values.add("create");
            return startsWith(values, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return startsWith(List.of("resident"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("guard")) {
            return startsWith(sender.hasPermission(PERMISSION)
                    ? List.of("hire", "rent") : List.of("hire"), args[1]);
        }
        if (sender.hasPermission(PERMISSION) && args.length == 3
                && args[0].equalsIgnoreCase("guard") && args[1].equalsIgnoreCase("hire")) {
            return startsWith(List.of("review", "resolve"), args[2]);
        }
        if (sender.hasPermission(PERMISSION) && args.length == 5
                && args[0].equalsIgnoreCase("guard") && args[1].equalsIgnoreCase("hire")
                && args[2].equalsIgnoreCase("resolve")) {
            return startsWith(List.of("paid", "failed", "refunded"), args[4]);
        }
        if (sender.hasPermission(PERMISSION) && args.length == 3
                && args[0].equalsIgnoreCase("guard") && args[1].equalsIgnoreCase("rent")) {
            return startsWith(List.of("review", "resolve"), args[2]);
        }
        if (sender.hasPermission(PERMISSION) && args.length == 5
                && args[0].equalsIgnoreCase("guard") && args[1].equalsIgnoreCase("rent")
                && args[2].equalsIgnoreCase("resolve")) {
            return startsWith(List.of("paid", "failed"), args[4]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("guard")
                && args[1].equalsIgnoreCase("hire")) {
            return startsWith(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), args[2]);
        }
        return List.of();
    }

    private List<String> startsWith(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }
}
