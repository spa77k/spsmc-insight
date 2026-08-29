package dev.spa.insight;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * config.yml の読み取りを1箇所にまとめる。値はプラグインの起動時に固定する。
 */
public class InsightConfig {

    private final FileConfiguration config;

    public InsightConfig(FileConfiguration config) {
        this.config = config;
    }

    public long flushIntervalMillis() {
        return Math.max(5L, config.getLong("flush-interval-seconds", 60L)) * 1000L;
    }

    public long flushIntervalTicks() {
        return Math.max(5L, config.getLong("flush-interval-seconds", 60L)) * 20L;
    }

    public long activeTimeoutMillis() {
        return Math.max(5L, config.getLong("active-timeout-seconds", 60L)) * 1000L;
    }

    public boolean exportEnabled() {
        return config.getBoolean("export.enabled", true);
    }

    public int exportHour() {
        return clamp(config.getInt("export.hour", 0), 0, 23);
    }

    public int exportMinute() {
        return clamp(config.getInt("export.minute", 30), 0, 59);
    }

    public String exportDirectory() {
        return config.getString("export.directory", "exports");
    }

    public long fileSizeWarnBytes() {
        return Math.max(1L, config.getLong("export.file-size-warn-mb", 48L)) * 1024L * 1024L;
    }

    public boolean retentionEnabled() {
        return config.getBoolean("retention.enabled", true);
    }

    public int retentionDetailDays() {
        return Math.max(30, config.getInt("retention.detail-days", 365));
    }

    public boolean backfillEnabled() {
        return config.getBoolean("backfill.enabled", true);
    }

    public boolean backfillCoreProtect() {
        return config.getBoolean("backfill.use-coreprotect", true);
    }

    public boolean backfillVanillaStats() {
        return config.getBoolean("backfill.use-vanilla-stats", true);
    }

    public boolean backfillEssentials() {
        return config.getBoolean("backfill.use-essentials", true);
    }

    public boolean sourceEnabled(String id) {
        return config.getBoolean("sources." + id, true);
    }

    public boolean tracking(String id) {
        return config.getBoolean("tracking." + id, true);
    }

    public int materialBreakdownTop() {
        return Math.max(0, config.getInt("tracking.material-breakdown-top", 40));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
