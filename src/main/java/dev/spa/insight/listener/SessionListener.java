package dev.spa.insight.listener;

import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.model.LiveSession;
import dev.spa.insight.model.Metrics;
import dev.spa.insight.tracking.SessionTracker;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接続・切断・ワールド移動。セッションの骨格になる出来事を扱う。
 */
public class SessionListener implements Listener {

    private final SessionTracker tracker;
    private final Map<UUID, String> pendingKickReason = new ConcurrentHashMap<>();

    public SessionListener(SessionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        pendingKickReason.remove(event.getPlayer().getUniqueId());
        tracker.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        pendingKickReason.put(event.getPlayer().getUniqueId(), "kick");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String reason = pendingKickReason.remove(uuid);
        tracker.onQuit(event.getPlayer(), reason == null ? "quit" : reason);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String world = player.getWorld().getName();
        session.switchWorld(world, now);
        session.increment(Metrics.WORLD_CHANGES);
        session.touch(now, Long.MAX_VALUE);

        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_WORLD_CHANGE, now, "live", world);
        World.Environment environment = player.getWorld().getEnvironment();
        if (environment == World.Environment.NETHER) {
            tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_NETHER, now, "live", world);
        } else if (environment == World.Environment.THE_END) {
            tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_END, now, "live", world);
        }
        String lowered = world.toLowerCase(java.util.Locale.ROOT);
        if (lowered.startsWith("build")) {
            tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_BUILD_WORLD, now, "live", world);
        } else if (lowered.startsWith("resource")) {
            tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_RESOURCE_WORLD, now, "live", world);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        LiveSession session = tracker.session(event.getPlayer());
        if (session == null) {
            return;
        }
        session.increment(Metrics.TELEPORTS);
        session.addBreakdown("teleport_cause", event.getCause().name(), 1L);
        session.position(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        LiveSession session = tracker.session(event.getPlayer());
        if (session != null) {
            session.increment(Metrics.PORTAL_USES);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        session.increment(Metrics.BEDS_ENTERED);
        tracker.touch(player);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_BED, System.currentTimeMillis(), "live");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        LiveSession session = tracker.session(event.getPlayer());
        if (session != null) {
            session.increment(Metrics.RESPAWNS);
            session.position(event.getRespawnLocation().getX(), event.getRespawnLocation().getY(),
                    event.getRespawnLocation().getZ());
        }
    }
}
