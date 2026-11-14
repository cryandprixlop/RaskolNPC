package ru.raskolnpc.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.npc.model.NpcRecord;
import ru.raskolnpc.npc.resident.ResidentDialogueService;
import ru.raskolnpc.npc.resident.quest.ResidentQuestService;
import ru.raskolnpc.npc.model.NpcId;
import ru.raskolnpc.npc.resident.ResidentScheduleCommandService;
import ru.raskolnpc.npc.resident.model.ResidentDestination;
import ru.raskolnpc.npc.resident.model.ResidentSchedule;
import ru.raskolnpc.guard.GuardRentService;
import ru.raskolnpc.integration.towny.TownyKingdomGeneralService;
import ru.raskolnpc.guard.model.GuardRentPayment;
import ru.raskolnpc.guard.model.GuardRentPaymentStatus;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public final class NpcCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "raskolnpc.admin";

    private final Plugin plugin;
    private final NpcCommandService service;
    private final NpcCommandMessages messages;
    private volatile GuardRentService guardRentService;
    private volatile ResidentScheduleCommandService residentScheduleService;
    private volatile ResidentDialogueService residentDialogueService;
    private volatile ResidentQuestService residentQuestService;
    private volatile TownyKingdomGeneralService kingdomGeneralService;

    public NpcCommand(Plugin plugin, NpcCommandService service,
                      NpcCommandMessages messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    public void setGuardRentService(GuardRentService guardRentService) {
        this.guardRentService = guardRentService;
    }

    public void setResidentScheduleService(ResidentScheduleCommandService service) {
        this.residentScheduleService = service;
    }

    public void setResidentDialogueService(ResidentDialogueService service) {
        this.residentDialogueService = service;
    }

    public void setResidentQuestService(ResidentQuestService service) {
        this.residentQuestService = service;
    }

    public void setKingdomGeneralService(TownyKingdomGeneralService service) {
        this.kingdomGeneralService = service;
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
        boolean guardRosterViewing = args.length >= 2
                && args[0].equalsIgnoreCase("guard")
                && (args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("info"))
                && sender instanceof Player
                && service.isGuardHiringEnabled();
        boolean guardHiring = args.length >= 2
                && args[0].equalsIgnoreCase("guard")
                && args[1].equalsIgnoreCase("hire")
                && sender instanceof Player
                && service.isGuardHiringEnabled();
        boolean residentQuestAccept = args.length == 5
                && args[0].equalsIgnoreCase("resident")
                && args[1].equalsIgnoreCase("quest")
                && (args[2].equalsIgnoreCase("accept") || args[2].equalsIgnoreCase("submit")
                        || args[2].equalsIgnoreCase("abandon"))
                && sender instanceof Player;
        boolean residentQuestReadOwn = args.length == 3
                && args[0].equalsIgnoreCase("resident")
                && args[1].equalsIgnoreCase("quest")
                && (args[2].equalsIgnoreCase("reputation") || args[2].equalsIgnoreCase("active"))
                && sender instanceof Player;
        boolean guardGeneralManagement = args.length >= 2
                && args[0].equalsIgnoreCase("guard")
                && args[1].equalsIgnoreCase("general")
                && sender instanceof Player;

        if (!admin && !townResidentCreation && !guardHiring && !guardRosterViewing
                && !guardGeneralManagement && !residentQuestAccept && !residentQuestReadOwn) {
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
            case "resident" -> resident(sender, args);
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


    private void resident(CommandSender sender, String[] args) {
        if (args.length == 5 && args[1].equalsIgnoreCase("quest")
                && (args[2].equalsIgnoreCase("accept") || args[2].equalsIgnoreCase("submit")
                        || args[2].equalsIgnoreCase("abandon"))) {
            if (!(sender instanceof Player player)) {
                messages.sendRaw(sender, "&cЭту операцию может выполнить только игрок.");
                return;
            }
            ResidentQuestService quests = residentQuestService;
            if (quests == null) {
                messages.sendRaw(sender, "&cСистема поручений временно недоступна.");
                return;
            }
            var residentId = NpcCommandParser.parseId(args[3]);
            if (residentId.isEmpty()) {
                messages.sendRaw(sender, "&cНекорректный UUID жителя.");
                return;
            }
            if (args[2].equalsIgnoreCase("abandon")) {
                quests.abandon(player, residentId.get(), args[4]).whenComplete((abandoned, error) ->
                        runMain(sender, () -> {
                            if (error != null) {
                                messages.sendRaw(sender, "&cНе удалось отменить поручение: "
                                        + rootMessage(error));
                            } else if (Boolean.TRUE.equals(abandoned)) {
                                messages.sendRaw(sender, "&eПоручение отменено. Его потребность снова доступна другим игрокам.");
                            } else {
                                messages.sendRaw(sender, "&cПоручение не найдено, уже завершено "
                                        + "или его сдача требует проверки.");
                            }
                        }));
                return;
            }
            if (args[2].equalsIgnoreCase("submit")) {
                quests.submit(player, residentId.get(), args[4]).whenComplete((result, error) ->
                        runMain(sender, () -> {
                            if (error != null) {
                                messages.sendRaw(sender, "&cНе удалось сдать ресурсы: " + rootMessage(error));
                                return;
                            }
                            switch (result) {
                                case SUBMITTED -> messages.sendRaw(sender,
                                        "&aРесурсы приняты. Репутация RaskolNPC увеличена.");
                                case NO_ACTIVE_QUEST -> messages.sendRaw(sender,
                                        "&cУ вас нет активного такого поручения.");
                                case SUBMISSION_PENDING -> messages.sendRaw(sender,
                                        "&eСдача уже выполняется или требует проверки.");
                                case NOT_ENOUGH_ITEMS -> messages.sendRaw(sender,
                                        "&cВ инвентаре недостаточно нужных ресурсов. Предметы не списаны.");
                                case INVALID_MATERIAL -> messages.sendRaw(sender,
                                        "&cМатериал поручения настроен неверно. Обратитесь к администрации.");
                                case PLAYER_OFFLINE -> messages.sendRaw(sender,
                                        "&eИгрок вышел. Поручение осталось активным.");
                                case REVIEW_REQUIRED -> messages.sendRaw(sender,
                                        "&cОперация требует проверки администрацией. Не повторяйте сдачу.");
                                case RESIDENT_NOT_FOUND -> messages.sendRaw(sender,
                                        "&cЖитель не найден или больше не доступен.");
                                case QUEST_NOT_FOUND -> messages.sendRaw(sender,
                                        "&cТакого поручения нет в каталоге.");
                            }
                        }));
                return;
            }
            quests.accept(player, residentId.get(), args[4]).whenComplete((result, error) ->
                    runMain(sender, () -> {
                        if (error != null) {
                            messages.sendRaw(sender, "&cНе удалось принять поручение: " + rootMessage(error));
                            return;
                        }
                        switch (result) {
                            case ACCEPTED -> messages.sendRaw(sender,
                                    "&aПоручение принято. Оно сохранено и останется активным после перезапуска.");
                            case ALREADY_ACTIVE -> messages.sendRaw(sender,
                                    "&eЭто поручение уже активно у вас.");
                            case RESIDENT_NOT_FOUND -> messages.sendRaw(sender,
                                    "&cЖитель не найден или больше не доступен.");
                            case QUEST_NOT_FOUND -> messages.sendRaw(sender,
                                    "&cТакого поручения нет в каталоге.");
                            case WRONG_PROFESSION -> messages.sendRaw(sender,
                                    "&cЭтот житель не выдаёт такое поручение.");
                            case PROFILE_UNAVAILABLE -> messages.sendRaw(sender,
                                    "&cПрофиль жителя пока недоступен.");
                            case NO_BUSINESS_DEMAND -> messages.sendRaw(sender,
                                    "&eУ предприятия достаточно запасов. Сейчас это поручение не требуется.");
                        }
                    }));
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("stock")) {
            if (!sender.hasPermission(PERMISSION)) { deny(sender); return; }
            ResidentQuestService quests = residentQuestService;
            if (quests == null) {
                messages.sendRaw(sender, "&cСистема запасов жителей временно недоступна.");
                return;
            }
            var residentId = NpcCommandParser.parseId(args[2]);
            if (residentId.isEmpty()) {
                messages.sendRaw(sender, "&cНекорректный UUID жителя.");
                return;
            }
            quests.stockReport(residentId.get()).whenComplete((report, error) -> runMain(sender, () -> {
                if (error != null) {
                    messages.sendRaw(sender, "&cНе удалось получить запасы предприятия: "
                            + rootMessage(error));
                    return;
                }
                if (report.isEmpty()) {
                    messages.sendRaw(sender, "&eДля профессии жителя пока нет настроенных поставок.");
                    return;
                }
                messages.sendRaw(sender, "&6Запасы предприятия жителя &f" + residentId.get() + "&6:");
                for (var entry : report) {
                    messages.sendRaw(sender, "&e" + entry.questId() + " &7Материал: &f"
                            + entry.material() + " &7Запас: &f" + entry.amount()
                            + (entry.target() == 0 ? " &7(без лимита)" :
                                    "/" + entry.target())
                            + (entry.deliveryAvailable() ? " &a— поставка нужна"
                                    : " &8— запаса достаточно"));
                }
            }));
            return;
        }

        if (args.length >= 3 && args[1].equalsIgnoreCase("quest")
                && (args[2].equalsIgnoreCase("reputation") || args[2].equalsIgnoreCase("active"))) {
            ResidentQuestService quests = residentQuestService;
            if (quests == null) {
                messages.sendRaw(sender, "&cСистема поручений временно недоступна.");
                return;
            }
            if (args.length > 4) {
                messages.sendRaw(sender, "&eИспользование: /raskolnpc resident quest reputation [UUID]");
                messages.sendRaw(sender, "&eИспользование: /raskolnpc resident quest active [UUID]");
                return;
            }
            UUID targetId;
            if (args.length == 4) {
                if (!sender.hasPermission(PERMISSION)) { deny(sender); return; }
                var parsed = NpcCommandParser.parseId(args[3]);
                if (parsed.isEmpty()) { messages.sendRaw(sender, "&cНекорректный UUID игрока."); return; }
                targetId = parsed.get().value();
            } else if (sender instanceof Player player) {
                targetId = player.getUniqueId();
            } else {
                messages.sendRaw(sender, "&cИз консоли укажите UUID игрока.");
                return;
            }

            if (args[2].equalsIgnoreCase("reputation")) {
                quests.reputation(targetId).whenComplete((points, error) -> runMain(sender, () -> {
                    if (error != null) messages.sendRaw(sender,
                            "&cНе удалось получить репутацию: " + rootMessage(error));
                    else messages.sendRaw(sender, "&6Репутация игрока &f" + targetId + "&6: &a" + points);
                }));
            } else {
                quests.activeAssignments(targetId).whenComplete((assignments, error) -> runMain(sender, () -> {
                    if (error != null) {
                        messages.sendRaw(sender, "&cНе удалось получить поручения: " + rootMessage(error));
                        return;
                    }
                    if (assignments.isEmpty()) {
                        messages.sendRaw(sender, "&eАктивных поручений нет.");
                        return;
                    }
                    messages.sendRaw(sender, "&6Активные поручения игрока &f" + targetId + "&6: "
                            + assignments.size());
                    assignments.forEach(assignment -> messages.sendRaw(sender,
                            "&e" + assignment.questId() + " &7Житель: &f" + assignment.residentId()
                                    + " &7Принято: &f" + assignment.acceptedAt()));
                }));
            }
            return;
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("quest")
                && args[2].equalsIgnoreCase("review")) {
            if (!sender.hasPermission(PERMISSION)) { deny(sender); return; }
            ResidentQuestService quests = residentQuestService;
            if (quests == null) {
                messages.sendRaw(sender, "&cСистема поручений временно недоступна.");
                return;
            }
            quests.reviewRequired(25).whenComplete((reviews, error) -> runMain(sender, () -> {
                if (error != null) {
                    messages.sendRaw(sender, "&cНе удалось загрузить спорные поручения: "
                            + rootMessage(error));
                    return;
                }
                if (reviews.isEmpty()) {
                    messages.sendRaw(sender, "&aСпорных поручений нет.");
                    return;
                }
                messages.sendRaw(sender, "&6Поручения, требующие проверки: &f" + reviews.size());
                for (var review : reviews) {
                    messages.sendRaw(sender, "&eИгрок: &f" + review.playerId()
                            + " &7Житель: &f" + review.residentId()
                            + " &7Поручение: &f" + review.questId()
                            + " &7Награда: &f" + (review.reputationReward() == null
                                    ? "legacy/unknown" : review.reputationReward()));
                    messages.sendRaw(sender, "&8Причина: " + (review.reason() == null
                            ? "не указана" : review.reason()));
                }
                messages.sendRaw(sender, "&eРазрешение: /raskolnpc resident quest resolve <playerUUID> <residentUUID> <questID> <complete|retry>");
            }));
            return;
        }

        if (args.length == 7 && args[1].equalsIgnoreCase("quest")
                && args[2].equalsIgnoreCase("resolve")) {
            if (!sender.hasPermission(PERMISSION)) { deny(sender); return; }
            var playerId = NpcCommandParser.parseId(args[3]);
            var residentId = NpcCommandParser.parseId(args[4]);
            if (playerId.isEmpty() || residentId.isEmpty()) {
                messages.sendRaw(sender, "&cНекорректный UUID игрока или жителя.");
                return;
            }
            boolean complete;
            if (args[6].equalsIgnoreCase("complete")) complete = true;
            else if (args[6].equalsIgnoreCase("retry")) complete = false;
            else {
                messages.sendRaw(sender, "&eВыберите complete или retry.");
                return;
            }
            ResidentQuestService quests = residentQuestService;
            if (quests == null) {
                messages.sendRaw(sender, "&cСистема поручений временно недоступна.");
                return;
            }
            quests.resolveReview(playerId.get().value(), residentId.get(), args[5], complete)
                    .whenComplete((resolved, error) -> runMain(sender, () -> {
                        if (error != null) {
                            messages.sendRaw(sender, "&cНе удалось разрешить поручение: "
                                    + rootMessage(error));
                        } else if (Boolean.TRUE.equals(resolved)) {
                            messages.sendRaw(sender, complete
                                    ? "&aСдача подтверждена. Поручение завершено, награда начислена один раз."
                                    : "&eСдача отклонена. Поручение снова доступно игроку.");
                        } else {
                            messages.sendRaw(sender, "&cСпорная операция не найдена, уже разрешена "
                                    + "или активное поручение отсутствует.");
                        }
                    }));
            return;
        }

        if (args.length == 2 && args[1].equalsIgnoreCase("reload-dialogues")) {
            ResidentDialogueService dialogues = residentDialogueService;
            if (dialogues == null) {
                messages.sendRaw(sender, "&cПерезагрузка диалогов временно недоступна.");
                return;
            }
            try {
                dialogues.reload();
                messages.sendRaw(sender, "&aДиалоги жителей успешно перезагружены.");
            } catch (IOException | InvalidConfigurationException exception) {
                String reason = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                messages.sendRaw(sender, "&cНе удалось перезагрузить диалоги: &f" + reason);
                messages.sendRaw(sender, "&eПредыдущая версия диалогов продолжает работать.");
            }
            return;
        }
        ResidentScheduleCommandService schedules = residentScheduleService;
        if (schedules == null) {
            messages.sendRaw(sender, "&cУправление расписаниями жителей временно недоступно.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("schedule")) {
            if (args.length == 4 && args[2].equalsIgnoreCase("list")) {
                var id = NpcCommandParser.parseId(args[3]);
                if (id.isEmpty()) { messages.sendRaw(sender, "&cНекорректный UUID жителя."); return; }
                schedules.listSchedule(id.get()).whenComplete((schedule, error) -> runMain(sender, () -> {
                    if (error != null) {
                        messages.sendRaw(sender, "&cНе удалось загрузить расписание: " + rootMessage(error)); return;
                    }
                    if (schedule.entries().isEmpty()) {
                        messages.sendRaw(sender, "&eУ жителя пока нет расписания."); return;
                    }
                    messages.sendRaw(sender, "&6Расписание жителя &f" + id.get() + "&6:");
                    for (ResidentSchedule.Entry entry : schedule.entries()) {
                        messages.sendRaw(sender, "&e" + entry.day() + " &f"
                                + ResidentScheduleCommandParser.formatTime(entry.startMinute()) + "-"
                                + ResidentScheduleCommandParser.formatTime(entry.endMinute())
                                + " &7" + entry.activity() + " → " + entry.destinationType());
                    }
                }));
                return;
            }
            if (args.length == 9 && args[2].equalsIgnoreCase("add")) {
                var id = NpcCommandParser.parseId(args[3]);
                if (id.isEmpty()) { messages.sendRaw(sender, "&cНекорректный UUID жителя."); return; }
                try {
                    var entry = new ResidentSchedule.Entry(
                            ResidentScheduleCommandParser.parseDay(args[4]),
                            ResidentScheduleCommandParser.parseStartTime(args[5]),
                            ResidentScheduleCommandParser.parseEndTime(args[6]),
                            ResidentScheduleCommandParser.parseActivity(args[7]),
                            ResidentScheduleCommandParser.parseDestinationType(args[8]));
                    schedules.addSchedule(id.get(), entry).whenComplete((saved, error) -> runMain(sender, () -> {
                        if (error != null) messages.sendRaw(sender, "&cНе удалось добавить запись: " + rootMessage(error));
                        else messages.sendRaw(sender, "&aЗапись расписания добавлена.");
                    }));
                } catch (IllegalArgumentException exception) {
                    messages.sendRaw(sender, "&c" + exception.getMessage());
                    messages.sendRaw(sender, "&eИспользование: /raskolnpc resident schedule add <UUID> <день> <начало> <конец> <занятие> <точка>");
                }
                return;
            }
            messages.sendRaw(sender, "&eИспользование: /raskolnpc resident schedule list <UUID>");
            messages.sendRaw(sender, "&eИспользование: /raskolnpc resident schedule add <UUID> <день> <начало> <конец> <занятие> <точка>");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("setpoint")) {
            if (!(sender instanceof Player player)) { messages.sendRaw(sender, "&cУстановить точку может только игрок."); return; }
            if (args.length < 4) {
                messages.sendRaw(sender, "&eИспользование: /raskolnpc resident setpoint <UUID> <HOME|WORK|MARKET|COMMUNITY> [название]"); return;
            }
            var id = NpcCommandParser.parseId(args[2]);
            if (id.isEmpty()) { messages.sendRaw(sender, "&cНекорректный UUID жителя."); return; }
            try {
                var type = ResidentScheduleCommandParser.parseDestinationType(args[3]);
                var location = player.getLocation().clone();
                String label = args.length > 4 ? NpcCommandParser.joinName(args, 4) : null;
                var point = new ResidentDestination(id.get(), type, location.getWorld().getUID(),
                        location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), label);
                schedules.setDestination(point).whenComplete((ignored, error) -> runMain(sender, () -> {
                    if (error != null) messages.sendRaw(sender, "&cНе удалось сохранить точку: " + rootMessage(error));
                    else messages.sendRaw(sender, "&aТочка &f" + type + " &aсохранена.");
                }));
            } catch (IllegalArgumentException exception) { messages.sendRaw(sender, "&c" + exception.getMessage()); }
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("delpoint")) {
            var id = NpcCommandParser.parseId(args[2]);
            if (id.isEmpty()) { messages.sendRaw(sender, "&cНекорректный UUID жителя."); return; }
            try {
                var type = ResidentScheduleCommandParser.parseDestinationType(args[3]);
                schedules.deleteDestination(id.get(), type).whenComplete((deleted, error) -> runMain(sender, () -> {
                    if (error != null) messages.sendRaw(sender, "&cНе удалось удалить точку: " + rootMessage(error));
                    else if (Boolean.TRUE.equals(deleted)) messages.sendRaw(sender, "&aТочка &f" + type + " &aудалена.");
                    else messages.sendRaw(sender, "&eТочка такого типа не была задана.");
                }));
            } catch (IllegalArgumentException exception) { messages.sendRaw(sender, "&c" + exception.getMessage()); }
            return;
        }
        messages.sendRaw(sender, "&eИспользование: /raskolnpc resident quest accept <UUID> <ID>");
        messages.sendRaw(sender, "&eИспользование: /raskolnpc resident quest reputation [UUID]");
        messages.sendRaw(sender, "&eИспользование: /raskolnpc resident quest active [UUID]");
        if (sender.hasPermission(PERMISSION)) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc resident quest review");
            messages.sendRaw(sender, "&eИспользование: /raskolnpc resident quest resolve <playerUUID> <residentUUID> <questID> <complete|retry>");
        }
        messages.sendRaw(sender, "&eИспользование: /raskolnpc resident schedule list|add ...");
        messages.sendRaw(sender, "&eИспользование: /raskolnpc resident setpoint|delpoint ...");
    }

    private void guard(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("general")) {
            guardGeneral(sender, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            guardList(sender);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("info")) {
            guardInfo(sender, args);
            return;
        }
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

    private void guardGeneral(CommandSender sender, String[] args) {
        TownyKingdomGeneralService generals = kingdomGeneralService;
        if (generals == null) {
            messages.sendRaw(sender, "&cУправление генералами недоступно: интеграция Towny выключена.");
            return;
        }
        if (args.length == 3 && args[2].equalsIgnoreCase("list")) {
            try {
                List<TownyKingdomGeneralService.GeneralEntry> entries = generals.list(sender);
                if (entries.isEmpty()) {
                    messages.sendRaw(sender, "&eГенералы пока не назначены.");
                    return;
                }
                messages.sendRaw(sender, "&6Генералы королевской стражи:");
                for (TownyKingdomGeneralService.GeneralEntry entry : entries) {
                    messages.sendRaw(sender, "&e" + entry.residentName() + " &7— " + entry.nationName());
                }
            } catch (RuntimeException exception) {
                messages.sendRaw(sender, "&cНе удалось получить список генералов: " + rootMessage(exception));
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&cНазначать и снимать генералов может только король нации в игре.");
            return;
        }
        if (args.length != 4 || !(args[2].equalsIgnoreCase("appoint")
                || args[2].equalsIgnoreCase("dismiss"))) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard general appoint <игрок>");
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard general dismiss <игрок>");
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard general list");
            return;
        }
        try {
            if (args[2].equalsIgnoreCase("appoint")) {
                generals.appoint(player, args[3]);
                messages.sendRaw(sender, "&aИгрок &f" + args[3] + " &aназначен генералом вашей нации.");
            } else {
                generals.dismiss(player, args[3]);
                messages.sendRaw(sender, "&aПолномочия генерала у игрока &f" + args[3] + " &aсняты.");
            }
        } catch (RuntimeException exception) {
            messages.sendRaw(sender, "&cОперация не выполнена: " + rootMessage(exception));
        }
    }

    private void guardList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&cПросмотр состава доступен только игроку.");
            return;
        }
        try {
            List<NpcRecord> guards = service.guardRoster(player);
            if (guards.isEmpty()) {
                messages.sendRaw(sender, "&eВ доступной вам нации стража пока не нанята.");
                return;
            }
            messages.sendRaw(sender, "&6Стража вашей нации: &f" + guards.size());
            for (NpcRecord guard : guards) {
                messages.sendRaw(sender, "&e" + guard.name() + " &7(" + guard.id()
                        + ") &8— город: " + guard.townId() + ", состояние: " + guard.state());
            }
        } catch (RuntimeException exception) {
            messages.sendRaw(sender, "&c" + exception.getMessage());
        }
    }

    private void guardInfo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&cПросмотр состава доступен только игроку.");
            return;
        }
        if (args.length < 3) {
            messages.sendRaw(sender, "&eИспользование: /raskolnpc guard info <UUID>");
            return;
        }
        var id = NpcCommandParser.parseId(args[2]);
        if (id.isEmpty()) {
            messages.sendRaw(sender, "&cНекорректный UUID стража.");
            return;
        }
        try {
            var guard = service.findVisibleGuard(player, id.get());
            if (guard.isEmpty()) {
                messages.sendRaw(sender, "&cСтраж не найден или у вас нет доступа к этой нации.");
                return;
            }
            NpcRecord record = guard.get();
            messages.sendRaw(sender, "&6Страж: &f" + record.name());
            messages.sendRaw(sender, "&7UUID: &f" + record.id());
            messages.sendRaw(sender, "&7Нация: &f" + record.nationId()
                    + " &7Город размещения: &f" + record.townId());
            messages.sendRaw(sender, "&7Состояние: &f" + record.state());
        } catch (RuntimeException exception) {
            messages.sendRaw(sender, "&c" + exception.getMessage());
        }
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
        messages.sendRaw(sender, "&e/" + label + " resident quest accept <UUID> <ID> &7— принять поручение жителя");
        messages.sendRaw(sender, "&e/" + label + " resident quest submit <UUID> <ID> &7— сдать ресурсы жителю");
        messages.sendRaw(sender, "&e/" + label + " resident quest abandon <UUID> <ID> &7— отменить принятое поручение");
        messages.sendRaw(sender, "&e/" + label + " resident quest reputation [UUID] &7— репутация игрока");
        messages.sendRaw(sender, "&e/" + label + " resident quest active [UUID] &7— активные поручения");
        if (sender.hasPermission(PERMISSION)) {
            messages.sendRaw(sender, "&e/" + label + " resident quest review &7— спорные сдачи");
            messages.sendRaw(sender, "&e/" + label + " resident quest resolve ... &7— разрешить спорную сдачу");
        }
        if (service.isGuardHiringEnabled()) {
            messages.sendRaw(sender, "&e/" + label + " guard hire <уровень> <имя> &7— нанять стража");
            messages.sendRaw(sender, "&e/" + label + " guard list &7— состав стражи вашей нации");
            messages.sendRaw(sender, "&e/" + label + " guard info <UUID> &7— карточка стража");
            messages.sendRaw(sender, "&e/" + label + " guard general appoint|dismiss <игрок> &7— управление генералами");
            messages.sendRaw(sender, "&e/" + label + " guard general list &7— список генералов");
        }
        if (sender.hasPermission(PERMISSION)) {
            messages.sendRaw(sender, "&e/" + label + " list &7— список NPC");
            messages.sendRaw(sender, "&e/" + label + " info <UUID> &7— информация");
            messages.sendRaw(sender, "&e/" + label + " delete <UUID> &7— удалить NPC");
            messages.sendRaw(sender, "&e/" + label + " resident schedule list <UUID> &7— расписание жителя");
            messages.sendRaw(sender, "&e/" + label + " resident schedule add ... &7— добавить запись");
            messages.sendRaw(sender, "&e/" + label + " resident setpoint/delpoint ... &7— точки жителя");
            messages.sendRaw(sender, "&e/" + label + " resident reload-dialogues &7— перезагрузить диалоги");
            messages.sendRaw(sender, "&e/" + label + " resident stock <UUID> &7— запасы предприятия");
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
        boolean canAcceptResidentQuest = sender instanceof Player && residentQuestService != null;
        if (!sender.hasPermission(PERMISSION) && !canCreateTownResident && !canHireGuard
                && !canAcceptResidentQuest) {
            return List.of();
        }
        if (args.length == 1) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            if (sender.hasPermission(PERMISSION)) values.addAll(List.of("create", "list", "info", "delete"));
            if (sender.hasPermission(PERMISSION) || canAcceptResidentQuest) values.add("resident");
            if (canHireGuard || sender.hasPermission(PERMISSION)) values.add("guard");
            if (canCreateTownResident && !values.contains("create")) values.add("create");
            return startsWith(values, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return startsWith(List.of("resident"), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("resident")) {
            java.util.ArrayList<String> residentActions = new java.util.ArrayList<>();
            if (sender.hasPermission(PERMISSION)) {
                residentActions.addAll(List.of("schedule", "setpoint", "delpoint", "reload-dialogues", "stock"));
            }
            if (canAcceptResidentQuest) residentActions.add("quest");
            return startsWith(residentActions, args[1]);
        }
        if (canAcceptResidentQuest && args.length == 3
                && args[0].equalsIgnoreCase("resident") && args[1].equalsIgnoreCase("quest")) {
            return startsWith(sender.hasPermission(PERMISSION)
                    ? List.of("accept", "submit", "abandon", "reputation", "active", "review", "resolve")
                    : List.of("accept", "submit", "abandon", "reputation", "active"), args[2]);
        }
        if (sender.hasPermission(PERMISSION) && args.length == 3
                && args[0].equalsIgnoreCase("resident") && args[1].equalsIgnoreCase("schedule"))
            return startsWith(List.of("list", "add"), args[2]);
        if (sender.hasPermission(PERMISSION) && args.length == 5
                && args[0].equalsIgnoreCase("resident") && args[1].equalsIgnoreCase("schedule")
                && args[2].equalsIgnoreCase("add"))
            return startsWith(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY",
                    "пн", "вт", "ср", "чт", "пт", "сб", "вс"), args[4]);
        if (sender.hasPermission(PERMISSION) && args.length == 8
                && args[0].equalsIgnoreCase("resident") && args[1].equalsIgnoreCase("schedule")
                && args[2].equalsIgnoreCase("add"))
            return startsWith(List.of("SLEEP", "WORK", "EAT", "SOCIALIZE", "LEISURE", "REST", "ERRAND"), args[7]);
        if (sender.hasPermission(PERMISSION) && args.length == 9
                && args[0].equalsIgnoreCase("resident") && args[1].equalsIgnoreCase("schedule")
                && args[2].equalsIgnoreCase("add"))
            return startsWith(List.of("HOME", "WORK", "MARKET", "COMMUNITY"), args[8]);
        if (sender.hasPermission(PERMISSION) && args.length == 4
                && args[0].equalsIgnoreCase("resident")
                && (args[1].equalsIgnoreCase("setpoint") || args[1].equalsIgnoreCase("delpoint")))
            return startsWith(List.of("HOME", "WORK", "MARKET", "COMMUNITY"), args[3]);
        if (args.length == 2 && args[0].equalsIgnoreCase("guard")) {
            return startsWith(sender.hasPermission(PERMISSION)
                    ? List.of("hire", "rent", "list", "info", "general")
                    : List.of("hire", "list", "info", "general"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("guard")
                && args[1].equalsIgnoreCase("general")) {
            return startsWith(List.of("appoint", "dismiss", "list"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("guard")
                && args[1].equalsIgnoreCase("general")
                && (args[2].equalsIgnoreCase("appoint") || args[2].equalsIgnoreCase("dismiss"))) {
            return startsWith(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[3]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("guard")
                && args[1].equalsIgnoreCase("info")) return List.of();
        if (sender.hasPermission(PERMISSION) && args.length == 3
                && args[0].equalsIgnoreCase("guard") && args[1].equalsIgnoreCase("hire")) {
            return startsWith(List.of("review", "resolve", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), args[2]);
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
