package dev.spa.insight.source;

import dev.spa.insight.milestone.Milestones;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 各プラグインのAPIをリフレクションで叩く連携。
 * コンパイル時依存を持たないので、相手が更新されても起動は止まらず、その項目だけ欠測になる。
 */
public final class PluginApiSources {

    private PluginApiSources() {
    }

    private static Plugin plugin(String name) {
        Plugin found = Bukkit.getPluginManager().getPlugin(name);
        return found != null && found.isEnabled() ? found : null;
    }

    /**
     * バージョンの取り方はPaperの版で変わるため、取れた方を使う。
     */
    private static String version(Plugin plugin) {
        Object meta = Reflect.call(plugin, "getPluginMeta");
        String version = meta == null ? null : Reflect.asString(Reflect.call(meta, "getVersion"));
        if (version != null) {
            return version;
        }
        Object description = Reflect.call(plugin, "getDescription");
        version = description == null ? null : Reflect.asString(Reflect.call(description, "getVersion"));
        return version == null ? "unknown" : version;
    }

    /**
     * Jobs Reborn の就職状況。職業に就いたかどうかは経済圏への入り口になる。
     */
    public static class Jobs implements SourceAdapter {

        @Override
        public String id() {
            return "jobs";
        }

        @Override
        public SourceStatus probe() {
            Plugin found = plugin("Jobs");
            if (found == null) {
                return SourceStatus.unavailable(id(), "Jobs が読み込まれていません");
            }
            if (Reflect.type("com.gamingmesh.jobs.Jobs") == null) {
                return SourceStatus.unavailable(id(), "com.gamingmesh.jobs.Jobs が見つかりません");
            }
            return SourceStatus.available(id(), "version=" + version(found));
        }

        @Override
        public void collect(Sink sink) {
            Class<?> jobs = Reflect.type("com.gamingmesh.jobs.Jobs");
            Object playerManager = Reflect.callStatic(jobs, "getPlayerManager");
            if (playerManager == null) {
                return;
            }
            for (UUID uuid : sink.targets()) {
                Object jobsPlayer = Reflect.call(playerManager, "getJobsPlayer", uuid);
                if (jobsPlayer == null) {
                    jobsPlayer = Reflect.call(playerManager, "getJobsPlayer", Bukkit.getOfflinePlayer(uuid).getName());
                }
                if (jobsPlayer == null) {
                    continue;
                }
                Object progression = Reflect.call(jobsPlayer, "getJobProgression");
                if (!(progression instanceof Collection<?> list) || list.isEmpty()) {
                    sink.value(uuid, "jobs_count", 0);
                    continue;
                }
                sink.value(uuid, "jobs_count", list.size());
                StringBuilder joined = new StringBuilder();
                for (Object entry : list) {
                    Object job = Reflect.call(entry, "getJob");
                    String name = Reflect.asString(Reflect.call(job, "getName"));
                    int level = Reflect.asInt(Reflect.call(entry, "getLevel"), 0);
                    if (name == null) {
                        continue;
                    }
                    if (joined.length() > 0) {
                        joined.append(',');
                    }
                    joined.append(name).append(':').append(level);
                }
                sink.value(uuid, "jobs", joined.toString());
                sink.milestone(uuid, Milestones.FIRST_JOB, sink.now(), joined.toString());
            }
        }
    }

    /**
     * GriefPrevention の土地保護。初めて自分の土地を持った時点は定着の分かれ目になりやすい。
     */
    public static class GriefPrevention implements SourceAdapter {

        @Override
        public String id() {
            return "griefprevention";
        }

        @Override
        public SourceStatus probe() {
            Plugin found = plugin("GriefPrevention");
            if (found == null) {
                return SourceStatus.unavailable(id(), "GriefPrevention が読み込まれていません");
            }
            if (dataStore() == null) {
                return SourceStatus.unavailable(id(), "dataStore を取得できません");
            }
            return SourceStatus.available(id(), "version=" + version(found));
        }

        private Object dataStore() {
            Class<?> type = Reflect.type("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object instance = Reflect.staticField(type, "instance");
            return instance == null ? null : Reflect.field(instance, "dataStore");
        }

        @Override
        public void collect(Sink sink) {
            Object dataStore = dataStore();
            if (dataStore == null) {
                return;
            }
            for (UUID uuid : sink.targets()) {
                Object data = Reflect.call(dataStore, "getPlayerData", uuid);
                if (data == null) {
                    continue;
                }
                int accrued = Reflect.asInt(Reflect.call(data, "getAccruedClaimBlocks"), -1);
                if (accrued >= 0) {
                    sink.value(uuid, "accrued_claim_blocks", accrued);
                }
                Object claims = Reflect.call(data, "getClaims");
                if (claims instanceof Collection<?> list) {
                    sink.value(uuid, "claims", list.size());
                    if (!list.isEmpty()) {
                        sink.milestone(uuid, Milestones.FIRST_CLAIM, sink.now(), "claims=" + list.size());
                    }
                }
            }
        }
    }

    /**
     * QuickShop-Hikari の出店数と、初めて店を出した時点。
     */
    public static class QuickShop implements SourceAdapter {

        @Override
        public String id() {
            return "quickshop";
        }

        @Override
        public SourceStatus probe() {
            Plugin found = plugin("QuickShop-Hikari");
            if (found == null) {
                found = plugin("QuickShop");
            }
            if (found == null) {
                return SourceStatus.unavailable(id(), "QuickShop が読み込まれていません");
            }
            if (shopManager() == null) {
                return SourceStatus.unavailable(id(), "ShopManager を取得できません");
            }
            return SourceStatus.available(id(), "version=" + version(found));
        }

        private Object shopManager() {
            Class<?> api = Reflect.type("com.ghostchu.quickshop.api.QuickShopAPI");
            Object instance = Reflect.callStatic(api, "getInstance");
            return instance == null ? null : Reflect.call(instance, "getShopManager");
        }

        @Override
        public void collect(Sink sink) {
            Object shopManager = shopManager();
            if (shopManager == null) {
                return;
            }
            Object shops = Reflect.call(shopManager, "getAllShops");
            if (!(shops instanceof Collection<?> list)) {
                return;
            }
            Map<UUID, Integer> owned = new HashMap<>();
            for (Object shop : list) {
                UUID owner = ownerOf(shop);
                if (owner != null) {
                    owned.merge(owner, 1, Integer::sum);
                }
            }
            for (UUID uuid : sink.targets()) {
                int count = owned.getOrDefault(uuid, 0);
                sink.value(uuid, "shops", count);
                if (count > 0) {
                    sink.milestone(uuid, Milestones.FIRST_SHOP_CREATE, sink.now(), "shops=" + count);
                }
            }
        }

        /**
         * Hikari は所有者を QUser で返す版と UUID で返す版がある。どちらでも拾えるようにする。
         */
        private UUID ownerOf(Object shop) {
            Object owner = Reflect.call(shop, "getOwner");
            if (owner instanceof UUID uuid) {
                return uuid;
            }
            Object unique = Reflect.call(owner, "getUniqueId");
            if (unique instanceof UUID uuid) {
                return uuid;
            }
            Object legacy = Reflect.call(shop, "getOwnerUUID");
            return legacy instanceof UUID uuid ? uuid : null;
        }
    }

    /**
     * Bolt のコンテナ保護数。チェストを守り始めたかどうかを見る。
     */
    public static class Bolt implements SourceAdapter {

        @Override
        public String id() {
            return "bolt";
        }

        @Override
        public SourceStatus probe() {
            Plugin found = plugin("Bolt");
            if (found == null) {
                return SourceStatus.unavailable(id(), "Bolt が読み込まれていません");
            }
            if (store() == null) {
                return SourceStatus.unavailable(id(), "Bolt の Store を取得できません");
            }
            return SourceStatus.available(id(), "version=" + version(found));
        }

        private Object store() {
            Plugin found = plugin("Bolt");
            Object bolt = Reflect.call(found, "getBolt");
            return bolt == null ? null : Reflect.call(bolt, "getStore");
        }

        @Override
        public void collect(Sink sink) {
            Object store = store();
            if (store == null) {
                return;
            }
            Object future = Reflect.call(store, "loadProtections");
            Object protections = future == null ? null : Reflect.call(future, "join");
            if (!(protections instanceof Collection<?> list)) {
                return;
            }
            Map<UUID, Integer> owned = new HashMap<>();
            for (Object protection : list) {
                Object owner = Reflect.call(protection, "getOwner");
                if (owner instanceof UUID uuid) {
                    owned.merge(uuid, 1, Integer::sum);
                }
            }
            for (UUID uuid : sink.targets()) {
                int count = owned.getOrDefault(uuid, 0);
                sink.value(uuid, "protections", count);
                if (count > 0) {
                    sink.milestone(uuid, Milestones.FIRST_LOCK, sink.now(), "protections=" + count);
                }
            }
        }
    }

    /**
     * DiscordSRV の連携済みDiscord ID。MCAuth とは別経路の紐付けを拾う。
     */
    public static class DiscordSrv implements SourceAdapter {

        @Override
        public String id() {
            return "discordsrv";
        }

        @Override
        public SourceStatus probe() {
            Plugin found = plugin("DiscordSRV");
            if (found == null) {
                return SourceStatus.unavailable(id(), "DiscordSRV が読み込まれていません");
            }
            if (linkManager() == null) {
                return SourceStatus.unavailable(id(), "AccountLinkManager を取得できません");
            }
            return SourceStatus.available(id(), "version=" + version(found));
        }

        private Object linkManager() {
            Class<?> type = Reflect.type("github.scarsz.discordsrv.DiscordSRV");
            Object instance = Reflect.callStatic(type, "getPlugin");
            return instance == null ? null : Reflect.call(instance, "getAccountLinkManager");
        }

        @Override
        public void collect(Sink sink) {
            Object manager = linkManager();
            if (manager == null) {
                return;
            }
            for (UUID uuid : sink.targets()) {
                String discordId = Reflect.asString(Reflect.call(manager, "getDiscordId", uuid));
                sink.value(uuid, "discord_id", discordId == null ? "" : discordId);
                if (discordId != null && !discordId.isBlank()) {
                    sink.milestone(uuid, Milestones.FIRST_DISCORD_LINK, sink.now(), "discordsrv");
                }
            }
        }
    }

    /**
     * WorldGuard のリージョン所有数。所有者に入っているリージョンだけを数える。
     */
    public static class WorldGuard implements SourceAdapter {

        @Override
        public String id() {
            return "worldguard";
        }

        @Override
        public SourceStatus probe() {
            Plugin found = plugin("WorldGuard");
            if (found == null) {
                return SourceStatus.unavailable(id(), "WorldGuard が読み込まれていません");
            }
            if (regionContainer() == null) {
                return SourceStatus.unavailable(id(), "RegionContainer を取得できません");
            }
            return SourceStatus.available(id(), "version=" + version(found));
        }

        private Object regionContainer() {
            Class<?> type = Reflect.type("com.sk89q.worldguard.WorldGuard");
            Object instance = Reflect.callStatic(type, "getInstance");
            Object platform = instance == null ? null : Reflect.call(instance, "getPlatform");
            return platform == null ? null : Reflect.call(platform, "getRegionContainer");
        }

        @Override
        public void collect(Sink sink) {
            Object container = regionContainer();
            if (container == null) {
                return;
            }
            Map<UUID, Integer> owned = new HashMap<>();
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                Object adapted = adapt(world);
                Object manager = adapted == null ? null : Reflect.call(container, "get", adapted);
                Object regions = manager == null ? null : Reflect.call(manager, "getRegions");
                if (!(regions instanceof Map<?, ?> map)) {
                    continue;
                }
                for (Object region : map.values()) {
                    Object owners = Reflect.call(region, "getOwners");
                    Object uniqueIds = owners == null ? null : Reflect.call(owners, "getUniqueIds");
                    if (uniqueIds instanceof Collection<?> ids) {
                        for (Object id : ids) {
                            if (id instanceof UUID uuid) {
                                owned.merge(uuid, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
            for (UUID uuid : sink.targets()) {
                sink.value(uuid, "regions_owned", owned.getOrDefault(uuid, 0));
            }
        }

        private Object adapt(org.bukkit.World world) {
            Class<?> adapter = Reflect.type("com.sk89q.worldedit.bukkit.BukkitAdapter");
            return Reflect.callStatic(adapter, "adapt", world);
        }
    }

    /**
     * Multiverse のワールド構成。プレイヤーごとの現在地と、サーバーのワールド一覧を残す。
     */
    public static class Multiverse implements SourceAdapter {

        @Override
        public String id() {
            return "multiverse";
        }

        @Override
        public SourceStatus probe() {
            Plugin found = plugin("Multiverse-Core");
            List<String> worlds = Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList();
            if (found == null) {
                return SourceStatus.available(id(), "Multiverse-Core なし。Bukkitのワールド一覧を使用: " + worlds);
            }
            return SourceStatus.available(id(), "version=" + version(found) + " worlds=" + worlds);
        }

        @Override
        public void collect(Sink sink) {
            for (UUID uuid : sink.targets()) {
                org.bukkit.entity.Player player = Bukkit.getPlayer(uuid);
                if (player == null) {
                    continue;
                }
                sink.value(uuid, "current_world", player.getWorld().getName());
                sink.value(uuid, "current_environment", player.getWorld().getEnvironment().name());
            }
        }
    }
}
