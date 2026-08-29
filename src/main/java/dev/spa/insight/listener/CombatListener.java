package dev.spa.insight.listener;

import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.model.LiveSession;
import dev.spa.insight.model.Metrics;
import dev.spa.insight.tracking.SessionTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * 死亡と戦闘。死因はファネルの離脱理由になりやすいので内訳も残す。
 */
public class CombatListener implements Listener {

    private final SessionTracker tracker;

    public CombatListener(SessionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        LiveSession session = tracker.session(player);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        session.increment(Metrics.DEATHS);
        String cause = player.getLastDamageCause() == null
                ? "UNKNOWN"
                : player.getLastDamageCause().getCause().name();
        session.addBreakdown(Metrics.ACTION_DEATH_CAUSE, cause, 1L);
        tracker.milestones().record(player.getUniqueId(), Milestones.FIRST_DEATH, now, "live", cause);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        LiveSession session = tracker.session(killer);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (event.getEntity() instanceof Player) {
            session.increment(Metrics.KILLS_PLAYER);
            tracker.milestones().record(killer.getUniqueId(), Milestones.FIRST_PVP_KILL, now, "live");
        } else {
            session.increment(Metrics.KILLS_MOB);
            session.addBreakdown("mob_kill", event.getEntityType().name(), 1L);
            tracker.milestones().record(killer.getUniqueId(), Milestones.FIRST_MOB_KILL, now, "live",
                    event.getEntityType().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player victim) {
            LiveSession session = tracker.session(victim);
            if (session != null) {
                session.add(Metrics.DAMAGE_TAKEN, Math.round(event.getFinalDamage() * 100.0));
            }
        }
        if (event.getDamager() instanceof Player attacker) {
            LiveSession session = tracker.session(attacker);
            if (session != null) {
                session.add(Metrics.DAMAGE_DEALT, Math.round(event.getFinalDamage() * 100.0));
                tracker.touch(attacker);
            }
        }
    }
}
