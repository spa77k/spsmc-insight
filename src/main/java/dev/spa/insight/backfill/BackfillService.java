package dev.spa.insight.backfill;

import dev.spa.insight.InsightConfig;
import dev.spa.insight.milestone.MilestoneRecorder;
import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.source.SourceRegistry;
import dev.spa.insight.storage.Database;
import dev.spa.insight.storage.PlayerStore;
import dev.spa.insight.util.Json;
import dev.spa.insight.util.TimeUtil;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * プラグイン導入前の記録を、サーバーに残っているファイルから1回だけ復元する。
 * 推定値であることが分かるよう、日別活動には source='backfill' を付けて残す。
 */
public class BackfillService {

    private static final String DONE_KEY = "backfill_done_at";
    private static final String REPORT_KEY = "backfill_report";

    private final Database database;
    private final PlayerStore playerStore;
    private final MilestoneRecorder milestones;
    private final SourceRegistry sources;
    private final InsightConfig config;
    private final File worldFolder;
    private final File serverRoot;
    private final Logger logger;

    public BackfillService(Database database, PlayerStore playerStore, MilestoneRecorder milestones,
                           SourceRegistry sources, InsightConfig config, File worldFolder, File serverRoot,
                           Logger logger) {
        this.database = database;
        this.playerStore = playerStore;
        this.milestones = milestones;
        this.sources = sources;
        this.config = config;
        this.worldFolder = worldFolder;
        this.serverRoot = serverRoot;
        this.logger = logger;
    }

    public record Report(int players, int days, int milestones, String coreProtectBasis, String note) {

        public String describe() {
            return "players=" + players + " days=" + days + " milestones=" + milestones
                    + " coreprotect=" + coreProtectBasis + (note.isBlank() ? "" : " note=" + note);
        }
    }

    public boolean alreadyDone() {
        return !database.getMeta(DONE_KEY, "").isBlank();
    }

    public String lastReport() {
        return database.getMeta(REPORT_KEY, "");
    }

    public Report run(boolean force) throws SQLException {
        if (!config.backfillEnabled() && !force) {
            return new Report(0, 0, 0, "skipped", "config.ymlで無効化されています");
        }
        if (alreadyDone() && !force) {
            return new Report(0, 0, 0, "skipped", "実行済みです。--force で再実行できます");
        }

        logger.info("遡及の探索先: world=" + worldFolder + " serverRoot=" + serverRoot);

        Map<UUID, String> names = readUserCache();
        Set<UUID> candidates = new LinkedHashSet<>(names.keySet());
        VanillaStatsReader vanilla = new VanillaStatsReader(worldFolder);
        collectUuids(candidates, vanilla.statsFolder(), ".json");
        collectUuids(candidates, vanilla.advancementsFolder(), ".json");
        collectUuids(candidates, vanilla.playerDataFolder(), ".dat");
        if (sources.essentials() != null) {
            collectUuids(candidates, sources.essentials().userdataFolder(), ".yml");
        }
        logger.info("遡及の候補: usercache=" + names.size()
                + " stats=" + count(vanilla.statsFolder(), ".json")
                + " advancements=" + count(vanilla.advancementsFolder(), ".json")
                + " playerdata=" + count(vanilla.playerDataFolder(), ".dat")
                + " 合計=" + candidates.size());

        Map<UUID, Long> firstSeen = new HashMap<>();
        Map<UUID, Long> lastSeen = new HashMap<>();

        String coreProtectBasis = "unavailable";
        int days = 0;
        if (config.backfillCoreProtect() && sources.coreProtect() != null
                && sources.coreProtect().databaseFile() != null) {
            CoreProtectBackfill coreProtect = new CoreProtectBackfill(sources.coreProtect().databaseFile());
            if (coreProtect.usable()) {
                DayWriter writer = new DayWriter(firstSeen, lastSeen);
                CoreProtectBackfill.Result result = coreProtect.run(writer);
                coreProtectBasis = result.basis();
                days = result.days();
                candidates.addAll(firstSeen.keySet());
            }
        }

        int milestoneCount = 0;
        int playerCount = 0;
        for (UUID uuid : candidates) {
            long first = firstSeen.getOrDefault(uuid, 0L);
            long last = lastSeen.getOrDefault(uuid, 0L);

            VanillaStatsReader.VanillaProfile profile = null;
            if (config.backfillVanillaStats() && vanilla.usable()) {
                profile = vanilla.read(uuid);
                if (profile.earliestAdvancementAt() > 0) {
                    first = min(first, profile.earliestAdvancementAt());
                }
                last = Math.max(last, profile.latestAdvancementAt());
                last = Math.max(last, vanilla.lastPlayedApproximation(uuid));
            }

            if (config.backfillEssentials() && sources.essentials() != null) {
                long[] essentials = readEssentialsTimestamps(sources.essentials().userdataFolder(), uuid);
                if (essentials[0] > 0) {
                    first = min(first, essentials[0]);
                }
                last = Math.max(last, essentials[1]);
            }

            if (first <= 0) {
                continue;
            }
            playerStore.seenAt(uuid, names.get(uuid), first, "backfill");
            if (last > first) {
                playerStore.addTotals(uuid, 0L, 0L, last);
            }
            playerCount++;

            milestones.recordDirect(uuid, Milestones.FIRST_JOIN, first, "backfill", "推定");
            milestoneCount++;
            if (profile != null) {
                milestoneCount += writeVanillaFacts(uuid, profile);
            }
        }

        String note = candidates.isEmpty() ? "対象プレイヤーが見つかりませんでした" : "";
        Report report = new Report(playerCount, days, milestoneCount, coreProtectBasis, note);
        database.setMeta(DONE_KEY, String.valueOf(System.currentTimeMillis()));
        database.setMeta(REPORT_KEY, report.describe());
        logger.info("遡及を完了しました: " + report.describe());
        return report;
    }

    private int writeVanillaFacts(UUID uuid, VanillaStatsReader.VanillaProfile profile) throws SQLException {
        int recorded = 0;
        snapshot(uuid, "vanilla_stats", "play_time_ms", profile.playTimeMillis());
        snapshot(uuid, "vanilla_stats", "leave_game", profile.leaveGameCount());
        snapshot(uuid, "vanilla_stats", "deaths", profile.deaths());
        snapshot(uuid, "vanilla_stats", "mob_kills", profile.mobKills());
        snapshot(uuid, "vanilla_stats", "blocks_mined", profile.blocksMined());
        snapshot(uuid, "vanilla_stats", "items_crafted", profile.itemsCrafted());
        snapshot(uuid, "vanilla_stats", "advancements", profile.advancementCount());

        if (profile.earliestAdvancementAt() > 0) {
            milestones.recordDirect(uuid, Milestones.FIRST_ADVANCEMENT, profile.earliestAdvancementAt(),
                    "backfill", "advancements/*.json");
            recorded++;
        }
        Long stone = firstMatching(profile.firstAdvancementTimes(), "story/mine_stone");
        if (stone != null) {
            milestones.recordDirect(uuid, Milestones.FIRST_BLOCK_BREAK, stone, "backfill", "story/mine_stone");
            recorded++;
        }
        Long nether = firstMatching(profile.firstAdvancementTimes(), "story/enter_the_nether");
        if (nether != null) {
            milestones.recordDirect(uuid, Milestones.FIRST_NETHER, nether, "backfill", "story/enter_the_nether");
            recorded++;
        }
        Long end = firstMatching(profile.firstAdvancementTimes(), "story/enter_the_end");
        if (end != null) {
            milestones.recordDirect(uuid, Milestones.FIRST_END, end, "backfill", "story/enter_the_end");
            recorded++;
        }
        if (profile.playTimeMillis() >= 3_600_000L) {
            milestones.recordDirect(uuid, Milestones.PLAYTIME_1H, profile.latestAdvancementAt(), "backfill", "stats");
            recorded++;
        }
        if (profile.playTimeMillis() >= 36_000_000L) {
            milestones.recordDirect(uuid, Milestones.PLAYTIME_10H, profile.latestAdvancementAt(), "backfill", "stats");
            recorded++;
        }
        if (profile.playTimeMillis() >= 180_000_000L) {
            milestones.recordDirect(uuid, Milestones.PLAYTIME_50H, profile.latestAdvancementAt(), "backfill", "stats");
            recorded++;
        }
        return recorded;
    }

    private static Long firstMatching(Map<String, Long> times, String suffix) {
        for (Map.Entry<String, Long> entry : times.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void snapshot(UUID uuid, String source, String key, Object value) throws SQLException {
        try (PreparedStatement statement = database.prepare("""
                INSERT INTO external_snapshots (uuid, source, key, value, taken_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid, source, key) DO UPDATE SET value = excluded.value, taken_at = excluded.taken_at
                """, uuid.toString(), source, key, String.valueOf(value), System.currentTimeMillis())) {
            statement.executeUpdate();
        }
    }

    /**
     * 遡及の日別活動。live で入っている行は上書きしない。
     */
    private final class DayWriter implements CoreProtectBackfill.DayConsumer {

        private final Map<UUID, Long> firstSeen;
        private final Map<UUID, Long> lastSeen;

        private DayWriter(Map<UUID, Long> firstSeen, Map<UUID, Long> lastSeen) {
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }

        @Override
        public void accept(UUID uuid, String dayKey, int sessions, long playtimeMillis) throws SQLException {
            long dayStart = TimeUtil.dayStartMillis(dayKey);
            firstSeen.merge(uuid, dayStart, Math::min);
            lastSeen.merge(uuid, dayStart, Math::max);
            try (PreparedStatement statement = database.prepare("""
                    INSERT INTO daily_activity (uuid, day, sessions, playtime_ms, active_ms, source)
                    VALUES (?, ?, ?, ?, 0, 'backfill')
                    ON CONFLICT(uuid, day) DO NOTHING
                    """, uuid.toString(), dayKey, sessions, playtimeMillis)) {
                statement.executeUpdate();
            }
        }
    }

    private static long min(long current, long candidate) {
        if (candidate <= 0) {
            return current;
        }
        return current <= 0 ? candidate : Math.min(current, candidate);
    }

    private Map<UUID, String> readUserCache() {
        Map<UUID, String> names = new HashMap<>();
        File file = new File(serverRoot, "usercache.json");
        if (!file.isFile()) {
            return names;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            List<Object> entries = Json.asArray(Json.parse(reader));
            for (Object entry : entries) {
                Map<String, Object> row = Json.asObject(entry);
                String raw = Json.asString(row.get("uuid"), null);
                String name = Json.asString(row.get("name"), null);
                if (raw == null) {
                    continue;
                }
                try {
                    names.put(UUID.fromString(raw), name);
                } catch (IllegalArgumentException ignored) {
                    // 壊れた行は読み飛ばす。
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warning("usercache.json を読めませんでした: " + e.getMessage());
        }
        return names;
    }

    private static String count(File folder, String suffix) {
        if (folder == null || !folder.isDirectory()) {
            return "なし(" + folder + ")";
        }
        String[] files = folder.list((dir, name) -> name.endsWith(suffix));
        return String.valueOf(files == null ? 0 : files.length);
    }

    private static void collectUuids(Set<UUID> target, File folder, String suffix) {
        if (folder == null || !folder.isDirectory()) {
            return;
        }
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(suffix)) {
                continue;
            }
            try {
                target.add(UUID.fromString(name.substring(0, name.length() - suffix.length())));
            } catch (IllegalArgumentException ignored) {
                // UUID以外のファイルは無視する。
            }
        }
    }

    private static long[] readEssentialsTimestamps(File userdata, UUID uuid) {
        File file = new File(userdata, uuid + ".yml");
        if (!file.isFile()) {
            return new long[]{0L, 0L};
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        long login = data.getLong("timestamps.login", 0L);
        long logout = data.getLong("timestamps.logout", 0L);
        return new long[]{login, Math.max(login, logout)};
    }
}
