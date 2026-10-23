package dev.spa.insight.listener;

import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.model.LiveSession;
import dev.spa.insight.model.Metrics;
import dev.spa.insight.source.Reflect;
import dev.spa.insight.storage.Database;
import dev.spa.insight.tracking.SessionTracker;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * QuickShop-Hikari のショップ作成と取引。誰が店を出し、誰が店で売り買いしたかを記録する。
 * QuickShop にコンパイル時依存しないよう、イベントクラスは実行時に読み込んで registerEvent で受ける。
 */
public final class QuickShopListener implements Listener {

    static final String SOURCE = "quickshop_events";
    private static final String CREATE_EVENT = "com.ghostchu.quickshop.api.event.management.ShopCreateEvent";
    private static final String PURCHASE_EVENT = "com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent";

    private final JavaPlugin plugin;
    private final SessionTracker tracker;
    private final Database database;
    private final Executor writer;

    public QuickShopListener(JavaPlugin plugin, SessionTracker tracker, Database database, Executor writer) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.database = database;
        this.writer = writer;
    }

    public void start() {
        Plugin quickShop = plugin.getServer().getPluginManager().getPlugin("QuickShop-Hikari");
        if (quickShop == null || !quickShop.isEnabled()) {
            status("missing", "QuickShop-Hikari is not enabled");
            return;
        }
        try {
            bind(quickShop, CREATE_EVENT, this::created);
            bind(quickShop, PURCHASE_EVENT, this::purchased);
            status("available", "QuickShop-Hikari " + quickShop.getDescription().getVersion());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            HandlerList.unregisterAll(this);
            status("error", e.toString());
        }
    }

    private void status(String status, String detail) {
        writer.execute(() -> database.recordSourceStatus(SOURCE, status, detail));
        plugin.getLogger().info("QuickShop events: " + status + " / " + detail);
    }

    private void bind(Plugin owner, String name, Consumer<Event> handler) throws ClassNotFoundException {
        Class<? extends Event> type = Class.forName(name, true, owner.getClass().getClassLoader()).asSubclass(Event.class);
        plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
            // QuickShop のイベントは AbstractQSEvent の HandlerList を共有しているので、ほかの種類を除く。
            if (!type.isInstance(event)) {
                return;
            }
            try {
                handler.accept(event);
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().warning("QuickShop の記録に失敗しました: " + e);
            }
        }, plugin, false);
    }

    /**
     * ShopCreateEvent は作成前（PRE_CANCELLABLE・MAIN）にも呼ばれる。作成が確定した POST だけを数える。
     */
    private void created(Event event) {
        if (!"POST".equals(Reflect.asString(Reflect.call(event, "phase")))) {
            return;
        }
        UUID creator = uuidOf(Reflect.call(event, "user"));
        if (creator == null) {
            return;
        }
        Object shop = unwrap(Reflect.call(event, "shop"));
        String type = shopType(shop);
        LiveSession session = sessionOf(creator);
        if (session != null) {
            session.increment(Metrics.SHOPS_CREATED);
        }
        tracker.milestones().record(creator, Milestones.FIRST_SHOP_CREATE, System.currentTimeMillis(), "live",
                "type=" + type);
    }

    private void purchased(Event event) {
        UUID trader = uuidOf(Reflect.call(event, "getPurchaser"));
        if (trader == null) {
            return;
        }
        int amount = Reflect.asInt(Reflect.call(event, "getAmount"), 0);
        String type = shopType(Reflect.call(event, "getShop"));
        LiveSession session = sessionOf(trader);
        if (session != null) {
            session.increment(Metrics.SHOP_TRADES);
            session.add(Metrics.SHOP_ITEMS_TRADED, amount);
            session.addBreakdown(Metrics.ACTION_SHOP_TRADE, type, 1L);
        }
        tracker.milestones().record(trader, Milestones.FIRST_SHOP_TRADE, System.currentTimeMillis(), "live",
                "type=" + type + " amount=" + amount);
    }

    private LiveSession sessionOf(UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        return tracker.session(player);
    }

    /**
     * QUser は実在プレイヤー以外（コンソールや仮想ユーザー）も表す。実在プレイヤーの UUID だけを返す。
     */
    static UUID uuidOf(Object user) {
        if (user == null) {
            return null;
        }
        if (unwrap(Reflect.call(user, "getUniqueIdIfRealPlayer")) instanceof UUID uuid) {
            return uuid;
        }
        if (Boolean.TRUE.equals(Reflect.call(user, "isRealPlayer"))
                && Reflect.call(user, "getUniqueId") instanceof UUID uuid) {
            return uuid;
        }
        return null;
    }

    static String shopType(Object shop) {
        String type = Reflect.asString(Reflect.call(shop, "getShopType"));
        return type == null ? "UNKNOWN" : type;
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }
}
