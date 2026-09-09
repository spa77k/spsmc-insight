package dev.spa.insight.probe;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.OfflinePlayerStub;
import com.earth2me.essentials.User;
import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.api.JobsPaymentEvent;
import com.gamingmesh.jobs.api.JobsPrePaymentEvent;
import com.gamingmesh.jobs.container.CurrencyType;
import com.gamingmesh.jobs.economy.BufferedPayment;
import com.gamingmesh.jobs.tasks.BufferedPaymentTask;
import net.ess3.api.events.UserBalanceUpdateEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.math.BigDecimal;
import java.util.*;

/** Local disposable-server probe; never packaged into the production JAR. */
public final class JobsAuditProbe extends JavaPlugin implements Listener {
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        later(80, () -> {
            for (String name : List.of("normal", "changed", "overlap", "unrelated", "cancelled", "failure", "async", "pre", "negative")) {
                UUID id = UUID.nameUUIDFromBytes(("InsightAuditProbe-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                OfflinePlayerStub stub = new OfflinePlayerStub(id, getServer());
                stub.setName("Audit_" + name);
                User user = ((Essentials) getServer().getPluginManager().getPlugin("Essentials")).getUser(stub);
                user.setMoney(name.equals("negative") ? BigDecimal.TEN : BigDecimal.ZERO);
                users.put(name, user);
            }
            var trackerField = dev.spa.insight.InsightPlugin.class.getDeclaredField("tracker");
            trackerField.setAccessible(true);
            var tracker = (dev.spa.insight.tracking.SessionTracker) trackerField.get(getServer().getPluginManager().getPlugin("SPSMCInsight"));
            tracker.onJoin(users.get("normal").getBase());
            getLogger().info("PROBE economy-async=" + Jobs.getGCManager().isEconomyAsync());
            pay("normal", 1.25);
            pay("changed", 5);
            pay("overlap", 2); pay("overlap", 3);
            users.get("unrelated").setMoney(new BigDecimal("50"));
            pay("negative", -2.5);
            pay("failure", -100);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> pay("async", 4));
            User preUser = users.get("pre");
            var action = new com.gamingmesh.jobs.container.ActionInfo() {
                public String getName() { return "DIAMOND_ORE"; }
                public String getNameWithSub() { return "DIAMOND_ORE"; }
                public com.gamingmesh.jobs.container.ActionType getType() { return com.gamingmesh.jobs.container.ActionType.BREAK; }
            };
            JobsPrePaymentEvent pre = new JobsPrePaymentEvent(preUser.getBase(), Jobs.getJob("Miner"), 10, 2, 0,
                    Bukkit.getWorlds().get(0).getBlockAt(0, 64, 0), null, null, action);
            Bukkit.getPluginManager().callEvent(pre);
            // Re-dispatching the same object tests identity deduplication, not a second action.
            Bukkit.getPluginManager().callEvent(pre);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                Jobs.getEconomy().pay(new BufferedPayment(users.get("cancelled").getBase(), money(8)));
                Jobs.getEconomy().payAll();
            });
        });
        later(120, () -> {
            for (var e : users.entrySet()) results.put(e.getKey(), e.getValue().getMoney().toPlainString());
            java.nio.file.Files.writeString(getDataFolder().toPath().resolve("balances.txt"), results.toString());
            getLogger().info("PROBE balances=" + results);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "insight export current");
        });
        later(150, () -> Bukkit.shutdown());
    }
    private Map<CurrencyType,Double> money(double value) {
        Map<CurrencyType,Double> map = new HashMap<>(); map.put(CurrencyType.MONEY, value); return map;
    }
    private void pay(String name, double value) {
        new BufferedPaymentTask(Jobs.getEconomy(), Jobs.getEconomy().getEconomy(),
                new BufferedPayment(users.get(name).getBase(), money(value))).run();
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void change(UserBalanceUpdateEvent e) {
        if (e.getPlayer().getUniqueId().equals(UUID.nameUUIDFromBytes("InsightAuditProbe-changed".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                && e.getNewBalance().signum() > 0) e.setNewBalance(e.getOldBalance().add(new BigDecimal("0.75")));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void cancel(JobsPaymentEvent e) {
        if (e.getPlayer().getUniqueId().equals(UUID.nameUUIDFromBytes("InsightAuditProbe-cancelled".getBytes(java.nio.charset.StandardCharsets.UTF_8)))) e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void pre(JobsPrePaymentEvent e) { e.setAmount(2.5); e.setCancelled(true); }
    private void later(long ticks, Action action) {
        getDataFolder().mkdirs();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { action.run(); }
            catch (Throwable e) { getLogger().log(java.util.logging.Level.SEVERE, "PROBE FAILED", e); }
        }, ticks);
    }
    private interface Action { void run() throws Exception; }
}
