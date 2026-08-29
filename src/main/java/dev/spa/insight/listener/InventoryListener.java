package dev.spa.insight.listener;

import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.model.LiveSession;
import dev.spa.insight.model.Metrics;
import dev.spa.insight.tracking.SessionTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/**
 * インベントリ操作とアイテムの出入り。
 */
public class InventoryListener implements Listener {

    private final SessionTracker tracker;

    public InventoryListener(SessionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        session.touch(System.currentTimeMillis(), Long.MAX_VALUE);
        session.increment(Metrics.INVENTORY_CLICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.PLAYER || type == InventoryType.CREATIVE) {
            return;
        }
        long now = System.currentTimeMillis();
        session.increment(Metrics.CONTAINERS_OPENED);
        session.addBreakdown("container", type.name(), 1L);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_CONTAINER, now, "live", type.name());
        if (type == InventoryType.MERCHANT) {
            session.increment(Metrics.VILLAGER_TRADES);
            tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_VILLAGER_TRADE, now, "live");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String material = event.getRecipe().getResult().getType().name();
        session.touch(now, Long.MAX_VALUE);
        session.increment(Metrics.ITEMS_CRAFTED);
        session.addBreakdown(Metrics.ACTION_CRAFT, material, 1L);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_CRAFT, now, "live", material);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        LiveSession session = tracker.session(player);
        if (session != null) {
            session.add(Metrics.ITEMS_PICKED_UP, event.getItem().getItemStack().getAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        LiveSession session = tracker.session(event.getPlayer());
        if (session != null) {
            session.increment(Metrics.ITEMS_DROPPED);
            tracker.touch(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        LiveSession session = tracker.session(event.getPlayer());
        if (session != null) {
            session.increment(Metrics.ITEMS_CONSUMED);
            session.addBreakdown("consume", event.getItem().getType().name(), 1L);
        }
    }
}
