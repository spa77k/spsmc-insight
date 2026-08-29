package dev.spa.insight.listener;

import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.model.LiveSession;
import dev.spa.insight.model.Metrics;
import dev.spa.insight.tracking.SessionTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;

/**
 * 進捗・釣り・繁殖など、続けている人にしか起きない出来事。
 */
public class ProgressionListener implements Listener {

    private final SessionTracker tracker;

    public ProgressionListener(SessionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        String key = event.getAdvancement().getKey().toString();
        if (key.contains("recipes/")) {
            // レシピ解禁は進捗として数えない。
            return;
        }
        long now = System.currentTimeMillis();
        session.increment(Metrics.ADVANCEMENTS);
        session.addBreakdown("advancement", key, 1L);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_ADVANCEMENT, now, "live", key);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        session.increment(Metrics.FISH_CAUGHT);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_FISH, System.currentTimeMillis(), "live");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player)) {
            return;
        }
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        session.increment(Metrics.ANIMALS_TAMED);
        session.addBreakdown("tame", event.getEntityType().name(), 1L);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_TAME, System.currentTimeMillis(), "live",
                event.getEntityType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) {
            return;
        }
        LiveSession session = tracker.session(player);
        if (session != null) {
            session.increment(Metrics.ANIMALS_BRED);
            session.addBreakdown("breed", event.getEntityType().name(), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEditBook(PlayerEditBookEvent event) {
        LiveSession session = tracker.session(event.getPlayer());
        if (session != null) {
            session.increment(Metrics.BOOKS_EDITED);
        }
    }
}
