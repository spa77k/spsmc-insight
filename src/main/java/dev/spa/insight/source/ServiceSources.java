package dev.spa.insight.source;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/**
 * Bukkit の ServicesManager 経由で取れる連携。Vault と LuckPerms がここに入る。
 */
public final class ServiceSources {

    private ServiceSources() {
    }

    private static Object provider(String className) {
        Class<?> type = Reflect.type(className);
        if (type == null) {
            return null;
        }
        RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(type);
        return registration == null ? null : registration.getProvider();
    }

    /**
     * Vault の Economy から残高を取る。経済圏に入っているかの目安になる。
     */
    public static class Vault implements SourceAdapter {

        private static final String ECONOMY = "net.milkbowl.vault.economy.Economy";

        @Override
        public String id() {
            return "vault";
        }

        @Override
        public SourceStatus probe() {
            Object economy = provider(ECONOMY);
            if (economy == null) {
                return SourceStatus.unavailable(id(), "Vault の Economy 登録がありません");
            }
            String name = Reflect.asString(Reflect.call(economy, "getName"));
            return SourceStatus.available(id(), "economy=" + name);
        }

        @Override
        public void collect(Sink sink) {
            Object economy = provider(ECONOMY);
            if (economy == null) {
                return;
            }
            for (UUID uuid : sink.targets()) {
                Object balance = Reflect.call(economy, "getBalance", Bukkit.getOfflinePlayer(uuid));
                if (balance instanceof Number number) {
                    sink.value(uuid, "balance", number.doubleValue());
                }
            }
        }
    }

    /**
     * LuckPerms の主グループ。McLevel の昇格結果がここに出る。
     */
    public static class LuckPerms implements SourceAdapter {

        private static final String API = "net.luckperms.api.LuckPerms";

        @Override
        public String id() {
            return "luckperms";
        }

        @Override
        public SourceStatus probe() {
            Object api = provider(API);
            if (api == null) {
                return SourceStatus.unavailable(id(), "LuckPerms のAPI登録がありません");
            }
            return SourceStatus.available(id(), "api=" + api.getClass().getName());
        }

        @Override
        public void collect(Sink sink) {
            Object api = provider(API);
            if (api == null) {
                return;
            }
            Object userManager = Reflect.call(api, "getUserManager");
            if (userManager == null) {
                return;
            }
            for (UUID uuid : sink.targets()) {
                Object user = Reflect.call(userManager, "getUser", uuid);
                if (user == null) {
                    continue;
                }
                String primary = Reflect.asString(Reflect.call(user, "getPrimaryGroup"));
                if (primary != null) {
                    sink.value(uuid, "primary_group", primary);
                }
            }
        }
    }
}
