package dev.spa.insight.listener;

import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.model.LiveSession;
import dev.spa.insight.model.Metrics;
import dev.spa.insight.tracking.SessionTracker;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * ブロック破壊・設置・インタラクト。鉱石だけは別カウンタでも数える。
 */
public class BlockListener implements Listener {

    private final SessionTracker tracker;

    public BlockListener(SessionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Material material = event.getBlock().getType();
        session.touch(now, Long.MAX_VALUE);
        session.increment(Metrics.BLOCKS_BROKEN);
        session.addBreakdown(Metrics.ACTION_BREAK, material.name(), 1L);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_BLOCK_BREAK, now, "live", material.name());
        if (isOre(material)) {
            session.increment(Metrics.ORES_MINED);
            tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_ORE, now, "live", material.name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Material material = event.getBlock().getType();
        session.touch(now, Long.MAX_VALUE);
        session.increment(Metrics.BLOCKS_PLACED);
        session.addBreakdown(Metrics.ACTION_PLACE, material.name(), 1L);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_BLOCK_PLACE, now, "live", material.name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        session.touch(now, Long.MAX_VALUE);
        session.increment(Metrics.INTERACTIONS);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_INTERACT, now, "live");
    }

    private static boolean isOre(Material material) {
        String name = material.name();
        return name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS");
    }
}
