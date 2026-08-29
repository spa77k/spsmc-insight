package dev.spa.insight.backfill;

import dev.spa.insight.util.Json;
import dev.spa.insight.util.TimeUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * サーバーが持つ stats/&lt;UUID&gt;.json と advancements/&lt;UUID&gt;.json を読む。
 * プラグイン導入前の実績はここにしか残っていないため、遡及の土台にする。
 */
public class VanillaStatsReader {

    private static final long TICKS_PER_SECOND = 20L;

    public record VanillaProfile(UUID uuid, long playTimeMillis, long leaveGameCount, long deaths,
                                 long mobKills, long blocksMined, long itemsCrafted,
                                 long earliestAdvancementAt, long latestAdvancementAt,
                                 int advancementCount, Map<String, Long> firstAdvancementTimes) {
    }

    private final File worldFolder;

    public VanillaStatsReader(File worldFolder) {
        this.worldFolder = worldFolder;
    }

    public File statsFolder() {
        return new File(worldFolder, "stats");
    }

    public File advancementsFolder() {
        return new File(worldFolder, "advancements");
    }

    public File playerDataFolder() {
        return new File(worldFolder, "playerdata");
    }

    public boolean usable() {
        return statsFolder().isDirectory() || advancementsFolder().isDirectory();
    }

    public VanillaProfile read(UUID uuid) {
        long playTimeMillis = 0L;
        long leaveGame = 0L;
        long deaths = 0L;
        long mobKills = 0L;
        long mined = 0L;
        long crafted = 0L;

        Map<String, Object> stats = readJsonObject(new File(statsFolder(), uuid + ".json"));
        if (!stats.isEmpty()) {
            Map<String, Object> groups = Json.asObject(stats.get("stats"));
            Map<String, Object> custom = Json.asObject(groups.get("minecraft:custom"));
            long ticks = Json.asLong(custom.get("minecraft:play_time"), -1L);
            if (ticks < 0) {
                ticks = Json.asLong(custom.get("minecraft:play_one_minute"), 0L);
            }
            playTimeMillis = ticks / TICKS_PER_SECOND * 1000L;
            leaveGame = Json.asLong(custom.get("minecraft:leave_game"), 0L);
            deaths = Json.asLong(custom.get("minecraft:deaths"), 0L);
            mobKills = Json.asLong(custom.get("minecraft:mob_kills"), 0L);
            mined = sum(Json.asObject(groups.get("minecraft:mined")));
            crafted = sum(Json.asObject(groups.get("minecraft:crafted")));
        }

        long earliest = 0L;
        long latest = 0L;
        int advancementCount = 0;
        Map<String, Long> firstTimes = new LinkedHashMap<>();
        Map<String, Object> advancements = readJsonObject(new File(advancementsFolder(), uuid + ".json"));
        for (Map.Entry<String, Object> entry : advancements.entrySet()) {
            if (entry.getKey().equals("DataVersion") || entry.getKey().contains("recipes/")) {
                continue;
            }
            Map<String, Object> advancement = Json.asObject(entry.getValue());
            Map<String, Object> criteria = Json.asObject(advancement.get("criteria"));
            long granted = 0L;
            for (Object value : criteria.values()) {
                long parsed = TimeUtil.parseLooseTimestamp(Json.asString(value, null));
                if (parsed > 0 && (granted == 0L || parsed < granted)) {
                    granted = parsed;
                }
            }
            if (granted == 0L) {
                continue;
            }
            if (Boolean.TRUE.equals(advancement.get("done"))) {
                advancementCount++;
            }
            firstTimes.put(entry.getKey(), granted);
            if (earliest == 0L || granted < earliest) {
                earliest = granted;
            }
            if (granted > latest) {
                latest = granted;
            }
        }

        return new VanillaProfile(uuid, playTimeMillis, leaveGame, deaths, mobKills, mined, crafted,
                earliest, latest, advancementCount, firstTimes);
    }

    /**
     * playerdata の更新時刻を最終ログインの近似として使う。NBTは読まない。
     */
    public long lastPlayedApproximation(UUID uuid) {
        File file = new File(playerDataFolder(), uuid + ".dat");
        return file.isFile() ? file.lastModified() : 0L;
    }

    private static long sum(Map<String, Object> values) {
        long total = 0L;
        for (Object value : values.values()) {
            total += Json.asLong(value, 0L);
        }
        return total;
    }

    private static Map<String, Object> readJsonObject(File file) {
        if (file == null || !file.isFile()) {
            return Map.of();
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return Json.asObject(Json.parse(reader));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }
}
