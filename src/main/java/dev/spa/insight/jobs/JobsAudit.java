package dev.spa.insight.jobs;

import dev.spa.insight.model.LiveSession;
import dev.spa.insight.source.Reflect;
import dev.spa.insight.storage.Database;
import dev.spa.insight.tracking.SessionTracker;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Passive, optional listeners. Never changes Jobs, Vault, balances or event values. */
public final class JobsAudit implements Listener {
    private final JavaPlugin plugin;
    private final SessionTracker tracker;
    private final ExecutorService writer;
    private final JobsAuditStore store;
    private final Database database;
    private final Map<Event, Map<String, Object>> drafts = new WeakHashMap<>();
    private final Set<Event> seen = Collections.newSetFromMap(new WeakHashMap<>());
    private final List<Pending> pending = new ArrayList<>();
    private Plugin essentials;
    private Class<?> jobsClass;
    private boolean closed;

    private static final class Pending {
        final Event event;
        final Map<String, Object> row;
        final LiveSession session;
        final boolean cancelledAtMonitor;
        boolean overlap;
        Pending(Event event, Map<String, Object> row, LiveSession session) {
            this.event = event; this.row = row; this.session = session;
            this.cancelledAtMonitor = event instanceof Cancellable c && c.isCancelled();
        }
    }

    public JobsAudit(JavaPlugin plugin, Database database, SessionTracker tracker, ExecutorService writer) {
        this.plugin = plugin; this.database = database; this.tracker = tracker;
        this.writer = writer; this.store = new JobsAuditStore(database);
    }

    public void start() {
        Plugin jobs = plugin.getServer().getPluginManager().getPlugin("Jobs");
        if (jobs == null || !jobs.isEnabled()) { status("missing", "Jobs is not enabled"); return; }
        try {
            jobsClass = Class.forName("com.gamingmesh.jobs.Jobs", true, jobs.getClass().getClassLoader());
            bind(jobs, "com.gamingmesh.jobs.api.JobsPrePaymentEvent", EventPriority.LOWEST, this::pre);
            bind(jobs, "com.gamingmesh.jobs.api.JobsPrePaymentEvent", EventPriority.MONITOR, this::payment);
            bind(jobs, "com.gamingmesh.jobs.api.JobsPaymentEvent", EventPriority.MONITOR, this::payment);
            essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
            boolean supported = jobs.getDescription().getVersion().equals("5.2.6.6") && essentials != null
                    && essentials.isEnabled() && essentials.getDescription().getVersion().equals("2.22.0");
            if (supported) {
                bind(essentials, "net.ess3.api.events.UserBalanceUpdateEvent", EventPriority.MONITOR, this::balance);
            }
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::finish, 1, 1);
            boolean async = Boolean.TRUE.equals(Reflect.call(Reflect.callStatic(jobsClass, "getGCManager"), "isEconomyAsync"));
            status(supported && !async ? "available" : "partial", "Jobs " + jobs.getDescription().getVersion()
                    + "; balance evidence=" + supported + "; economy-async=" + async
                    + "; action-to-payment attribution and limit reason unavailable");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            HandlerList.unregisterAll(this);
            status("error", e.toString());
        }
    }

    private void status(String status, String detail) {
        writer.execute(() -> database.recordSourceStatus("jobs_audit", status, detail));
        plugin.getLogger().info("Jobs audit: " + status + " / " + detail);
    }

    private void bind(Plugin owner, String name, EventPriority priority, java.util.function.Consumer<Event> handler)
            throws ClassNotFoundException {
        Class<? extends Event> type = Class.forName(name, true, owner.getClass().getClassLoader()).asSubclass(Event.class);
        plugin.getServer().getPluginManager().registerEvent(type, this, priority, (listener, event) -> {
            try { handler.accept(event); }
            catch (RuntimeException e) { plugin.getLogger().warning("Jobs audit observation failed: " + e); }
        }, plugin, false);
    }

    private synchronized void pre(Event event) {
        if (closed || drafts.containsKey(event)) return;
        Map<String, Object> row = base(event, "pre_payment");
        amounts(event, row);
        row.put("planned_money", row.get("event_money"));
        Object job = Reflect.call(event, "getJob");
        row.put("job", Reflect.call(job, "getName"));
        Object player = required(event, "getPlayer");
        Object manager = Reflect.callStatic(jobsClass, "getPlayerManager");
        Object jp = Reflect.call(manager, "getJobsPlayer", ((OfflinePlayer) player).getUniqueId());
        Object progression = Reflect.call(jp, "getJobProgression", job);
        row.put("job_level", Reflect.call(progression, "getLevel"));
        Object action = Reflect.call(event, "getActionInfo");
        row.put("action", Reflect.asString(Reflect.call(action, "getType")));
        row.put("target", Reflect.asString(Reflect.call(action, "getNameWithSub")));
        if (!event.isAsynchronous()) {
            for (String getter : List.of("getBlock", "getEntity", "getLivingEntity")) {
                Object target = Reflect.call(event, getter);
                Object world = Reflect.call(target, "getWorld");
                if (world != null) {
                    row.put("world", Reflect.call(world, "getName"));
                    if (row.get("target") == null) row.put("target", Reflect.asString(Reflect.call(target, "getType")));
                    break;
                }
            }
        }
        row.put("detail", "Planned amount observed at LOWEST; limit reason and final action income are unknown");
        drafts.put(event, row);
    }

    private synchronized void payment(Event event) {
        if (closed || !seen.add(event)) return;
        Map<String, Object> row = drafts.remove(event);
        if (row == null) row = base(event, "payment");
        amounts(event, row);
        row.put("status", "pending");
        row.putIfAbsent("detail", "Player payment batch before deposit; not confirmed income; no action correlation");
        Pending item = new Pending(event, row, session(row));
        save(item);
        pending.add(item);
    }

    private synchronized void balance(Event event) {
        if (closed || !seen.add(event)) return;
        OfflinePlayer player = (OfflinePlayer) required(event, "getPlayer");
        String uuid = player.getUniqueId().toString();
        boolean overlap = false;
        for (Pending p : pending) {
            if ("balance".equals(p.row.get("phase")) && uuid.equals(p.row.get("uuid"))) {
                p.overlap = true;
                overlap = true;
            }
        }
        if (!BalanceEvidence.jobsPaymentOrigin(Thread.currentThread().getStackTrace())) return;
        Map<String, Object> row = base(event, "balance");
        row.put("balance_before", decimal(required(event, "getOldBalance")));
        row.put("balance_after", decimal(required(event, "getNewBalance")));
        row.put("detail", "Jobs BufferedPaymentTask -> VaultEconomy -> Essentials; later balance check, not transaction receipt");
        Pending item = new Pending(event, row, session(row));
        item.overlap = overlap;
        save(item);
        pending.add(item);
    }

    private Map<String, Object> base(Event event, String phase) {
        OfflinePlayer player = (OfflinePlayer) required(event, "getPlayer");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("occurred_at", System.currentTimeMillis());
        row.put("uuid", player.getUniqueId().toString());
        row.put("phase", phase);
        row.put("status", "pending");
        LiveSession live = tracker.live().get(player.getUniqueId());
        row.put("world", live == null ? null : live.currentWorld());
        if (!event.isAsynchronous() && player.isOnline() && player.getPlayer() != null)
            row.put("world", player.getPlayer().getWorld().getName());
        return row;
    }

    private LiveSession session(Map<String, Object> row) {
        return tracker.live().get(UUID.fromString((String) row.get("uuid")));
    }

    private static void amounts(Event event, Map<String, Object> row) {
        row.put("event_money", decimal(required(event, "getAmount")));
        row.put("points", decimal(required(event, "getPoints")));
        // JobsPaymentEvent exposes EXP only in its currency map.
        if (row.get("phase").equals("pre_payment")) row.put("exp", decimal(required(event, "getExp")));
        else {
            Object values = Reflect.call(event, "getPayment");
            if (values instanceof Map<?, ?> map)
                map.forEach((currency, amount) -> { if (currency.toString().equals("EXP")) row.put("exp", decimal(amount)); });
        }
    }

    private synchronized void finish() {
        if (closed) return;
        for (Pending p : pending) {
            try {
                if ("balance".equals(p.row.get("phase"))) {
                    if (!p.event.isAsynchronous())
                        p.row.put("balance_after", decimal(required(p.event, "getNewBalance")));
                    Object user = Reflect.call(essentials, "getUser", UUID.fromString((String) p.row.get("uuid")));
                    BigDecimal observed = asDecimal(Reflect.call(user, "getMoney"));
                    BigDecimal expected = asDecimal(p.row.get("balance_after"));
                    String status = BalanceEvidence.status(p.event.isAsynchronous(), p.overlap, expected, observed);
                    p.row.put("status", status);
                    p.row.put("observed_balance", decimal(observed));
                    if (status.equals("balance_verified"))
                        p.row.put("verified_delta", expected.subtract(asDecimal(p.row.get("balance_before"))).toPlainString());
                } else {
                    // Async dispatch may still be running: preserve MONITOR snapshot and label it explicitly.
                    if (!p.event.isAsynchronous()) amounts(p.event, p.row);
                    boolean cancelled = p.event.isAsynchronous() ? p.cancelledAtMonitor
                            : p.event instanceof Cancellable c && c.isCancelled();
                    p.row.put("status", cancelled ? "cancelled" : p.event.isAsynchronous() ? "observed_monitor" : "observed");
                }
            } catch (RuntimeException e) {
                p.row.put("status", "unverified");
                p.row.put("detail", p.row.get("detail") + "; " + e);
            }
            save(p);
        }
        pending.clear();
    }

    private void save(Pending p) {
        Map<String, Object> copy = new LinkedHashMap<>(p.row);
        LiveSession session = p.session;
        writer.execute(() -> {
            if (session != null && session.sessionId() > 0) copy.put("session_id", session.sessionId());
            try { store.save(copy); }
            catch (Exception e) { plugin.getLogger().severe("Jobs audit DB write failed: " + e); }
        });
    }

    public synchronized void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        for (Pending p : pending) {
            p.row.put("status", "unverified_shutdown");
            save(p);
        }
        pending.clear(); drafts.clear(); seen.clear();
    }

    private static Object required(Object target, String method) {
        Object value = Reflect.call(target, method);
        if (value == null) throw new IllegalStateException("Missing " + method + " on " + target.getClass().getName());
        return value;
    }
    private static BigDecimal asDecimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }
    private static String decimal(Object value) {
        BigDecimal amount = asDecimal(value);
        return amount == null ? null : amount.toPlainString();
    }
}
