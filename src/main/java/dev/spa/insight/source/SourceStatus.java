package dev.spa.insight.source;

/**
 * 連携ソースの状態。読めなかった理由をそのままエクスポートへ載せる。
 */
public record SourceStatus(String id, String status, String detail) {

    public static final String AVAILABLE = "available";
    public static final String UNAVAILABLE = "unavailable";
    public static final String DISABLED = "disabled";
    public static final String ERROR = "error";

    public static SourceStatus available(String id, String detail) {
        return new SourceStatus(id, AVAILABLE, detail);
    }

    public static SourceStatus unavailable(String id, String detail) {
        return new SourceStatus(id, UNAVAILABLE, detail);
    }

    public static SourceStatus disabled(String id) {
        return new SourceStatus(id, DISABLED, "config.ymlで無効化されています");
    }

    public static SourceStatus error(String id, String detail) {
        return new SourceStatus(id, ERROR, detail);
    }

    public boolean usable() {
        return AVAILABLE.equals(status);
    }
}
