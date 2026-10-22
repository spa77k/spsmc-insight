package dev.spa.insight;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * config.yml の読み取りを1箇所にまとめる。値はプラグインの起動時に固定する。
 * 既定値を引数で渡すと jar の config.yml の値が使われないため、引数なしで読む。
 */
public class InsightConfig {

    private final FileConfiguration config;

    public InsightConfig(FileConfiguration config) {
        this.config = config;
    }

    public long flushIntervalTicks() {
        return Math.max(5L, config.getLong("flush-interval-seconds")) * 20L;
    }

    public long activeTimeoutMillis() {
        return Math.max(5L, config.getLong("active-timeout-seconds")) * 1000L;
    }

    public boolean exportEnabled() {
        return config.getBoolean("export.enabled");
    }

    public int exportHour() {
        return clamp(config.getInt("export.hour"), 0, 23);
    }

    public int exportMinute() {
        return clamp(config.getInt("export.minute"), 0, 59);
    }

    public String exportDirectory() {
        return config.getString("export.directory");
    }

    public long fileSizeWarnBytes() {
        return Math.max(1L, config.getLong("export.file-size-warn-mb")) * 1024L * 1024L;
    }

    public boolean retentionEnabled() {
        return config.getBoolean("retention.enabled");
    }

    public int retentionDetailDays() {
        return Math.max(30, config.getInt("retention.detail-days"));
    }

    public boolean backfillEnabled() {
        return config.getBoolean("backfill.enabled");
    }

    public boolean backfillCoreProtect() {
        return config.getBoolean("backfill.use-coreprotect");
    }

    public boolean backfillVanillaStats() {
        return config.getBoolean("backfill.use-vanilla-stats");
    }

    public boolean backfillEssentials() {
        return config.getBoolean("backfill.use-essentials");
    }

    public boolean sourceEnabled(String id) {
        return enabledUnlessWritten("sources." + id);
    }

    public boolean tracking(String id) {
        return enabledUnlessWritten("tracking." + id);
    }

    public int materialBreakdownTop() {
        return Math.max(0, config.getInt("tracking.material-breakdown-top"));
    }

    public boolean jobsAuditEnabled() {
        return config.getBoolean("jobs-audit.enabled");
    }

    /** 本番か jar の config.yml に書いてあればその値、どちらにも無い id は有効にする。 */
    private boolean enabledUnlessWritten(String path) {
        return !config.contains(path) || config.getBoolean(path);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
