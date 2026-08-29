package dev.spa.insight.listener;

import dev.spa.insight.model.LiveSession;
import dev.spa.insight.model.Metrics;
import dev.spa.insight.tracking.SessionTracker;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 移動距離とアクティブ判定。最も頻度が高いので、加算以外の処理を入れない。
 */
public class MovementListener implements Listener {

    private final SessionTracker tracker;
    private final long activeTimeoutMillis;

    public MovementListener(SessionTracker tracker, long activeTimeoutMillis) {
        this.tracker = tracker;
        this.activeTimeoutMillis = activeTimeoutMillis;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        Player player = event.getPlayer();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }

        session.touch(System.currentTimeMillis(), activeTimeoutMillis);

        if (!session.positionKnown()) {
            session.position(to.getX(), to.getY(), to.getZ());
            return;
        }
        double dx = to.getX() - session.lastX();
        double dy = to.getY() - session.lastY();
        double dz = to.getZ() - session.lastZ();
        session.position(to.getX(), to.getY(), to.getZ());

        double squared = dx * dx + dy * dy + dz * dz;
        if (squared <= 0.0001D || squared > 10000.0D) {
            // テレポート相当の飛びは距離に含めない。
            return;
        }
        long centimetres = Math.round(Math.sqrt(squared) * 100.0D);
        if (player.isInsideVehicle()) {
            session.add(Metrics.DISTANCE_VEHICLE_CM, centimetres);
        } else if (player.isGliding()) {
            session.add(Metrics.DISTANCE_FLIGHT_CM, centimetres);
        } else {
            session.add(Metrics.DISTANCE_CM, centimetres);
        }
    }
}
