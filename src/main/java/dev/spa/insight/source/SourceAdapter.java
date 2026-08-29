package dev.spa.insight.source;

import java.util.Collection;
import java.util.UUID;

/**
 * 他プラグインから値を読むための共通の口。
 * 実装は例外を投げてよい。呼び出し側が捕まえて、そのソースだけを欠測として扱う。
 */
public interface SourceAdapter {

    String id();

    /**
     * 使えるかどうかの判定。起動時に一度だけ呼ぶ。
     */
    SourceStatus probe();

    /**
     * 現在値の取り込み。書き込みスレッド上から定期的に呼ばれる。
     */
    void collect(Sink sink) throws Exception;

    interface Sink {

        /**
         * 取り込み対象。接続中のプレイヤーを基本とする。
         */
        Collection<UUID> targets();

        long now();

        void value(UUID uuid, String key, Object value);

        void milestone(UUID uuid, String key, long achievedAt, String detail);
    }
}
