package ru.raskolnpc.npc.resident.quest;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import ru.raskolnpc.storage.ResidentTradeTransaction;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Main-thread Bukkit inventory delivery with a persistent transaction marker.
 * Repeated delivery calls top up only the missing quantity, making retries safe
 * after a partial inventory save or an uncertain previous callback.
 */
public final class BukkitResidentTradeDeliveryProvider implements ResidentTradeDeliveryProvider {
    private final Plugin plugin;
    private final NamespacedKey transactionKey;

    public BukkitResidentTradeDeliveryProvider(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.transactionKey = new NamespacedKey(plugin, "resident_trade_id");
    }

    @Override
    public CompletableFuture<Result> deliver(ResidentTradeTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        CompletableFuture<Result> result = new CompletableFuture<>();
        Runnable deliveryTask = () -> {
            try {
                result.complete(deliverOnMainThread(transaction));
            } catch (RuntimeException exception) {
                result.complete(new Result(Outcome.UNKNOWN, safeMessage(exception)));
            }
        };
        if (Bukkit.isPrimaryThread()) {
            deliveryTask.run();
        } else {
            try {
                plugin.getServer().getScheduler().runTask(plugin, deliveryTask);
            } catch (RuntimeException exception) {
                result.complete(new Result(Outcome.UNKNOWN, safeMessage(exception)));
            }
        }
        return result;
    }

    private Result deliverOnMainThread(ResidentTradeTransaction transaction) {
        Player player = Bukkit.getPlayer(transaction.buyerId());
        if (player == null || !player.isOnline()) {
            return new Result(Outcome.FAILED, "Buyer is offline; no items were delivered.");
        }
        Material material = Material.matchMaterial(transaction.material());
        if (material == null || !material.isItem()) {
            return new Result(Outcome.FAILED, "Trade material is not a valid item: " + transaction.material());
        }

        Inventory inventory = player.getInventory();
        int alreadyDelivered = countMarkedItems(inventory, transaction.transactionId());
        int remaining = Math.max(0, transaction.amount() - alreadyDelivered);
        if (remaining == 0) return new Result(Outcome.DELIVERED, "Items already present in buyer inventory.");

        ItemStack stack = new ItemStack(material, remaining);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return new Result(Outcome.FAILED, "Could not create item metadata.");
        meta.getPersistentDataContainer().set(transactionKey, PersistentDataType.STRING,
                transaction.transactionId());
        stack.setItemMeta(meta);

        Inventory simulation = Bukkit.createInventory(null, inventory.getStorageContents().length);
        simulation.setStorageContents(cloneContents(inventory.getStorageContents()));
        Map<Integer, ItemStack> leftovers = simulation.addItem(stack.clone());
        if (!leftovers.isEmpty()) {
            return new Result(Outcome.FAILED, "Buyer inventory has insufficient space.");
        }

        Map<Integer, ItemStack> actualLeftovers = inventory.addItem(stack);
        if (!actualLeftovers.isEmpty()) {
            return new Result(Outcome.UNKNOWN, "Inventory accepted only part of the trade delivery.");
        }
        int delivered = countMarkedItems(inventory, transaction.transactionId());
        if (delivered < transaction.amount()) {
            return new Result(Outcome.UNKNOWN, "Inventory delivery quantity could not be verified.");
        }
        return new Result(Outcome.DELIVERED, "Delivered " + delivered + " marked item(s).");
    }

    private int countMarkedItems(Inventory inventory, String transactionId) {
        int total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) continue;
            String marker = item.getItemMeta().getPersistentDataContainer()
                    .get(transactionKey, PersistentDataType.STRING);
            if (transactionId.equals(marker)) {
                total = Math.addExact(total, item.getAmount());
            }
        }
        return total;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(900, message.length()));
    }
}
