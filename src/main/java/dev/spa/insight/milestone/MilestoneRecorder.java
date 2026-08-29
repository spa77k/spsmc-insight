package dev.spa.insight.milestone;

import dev.spa.insight.storage.Database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * 初回到達を1度だけ記録する。同じ項目を何度呼んでも最初の1回しか残らない。
 */
public class MilestoneRecorder {

    private final Database database;
    private final Executor writer;
    private final Logger logger;
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    public MilestoneRecorder(Database database, Executor writer, Logger logger) {
        this.database = database;
        this.writer = writer;
        this.logger = logger;
    }

    /**
     * 接続時にそのプレイヤーの既存到達を読み込む。以降の判定はメモリ上で済ませる。
     */
    public void warmUp(UUID uuid) {
        try (PreparedStatement statement = database.prepare("SELECT key FROM milestones WHERE uuid = ?", uuid.toString());
             var result = statement.executeQuery()) {
            while (result.next()) {
                known.add(cacheKey(uuid, result.getString(1)));
            }
        } catch (SQLException e) {
            logger.warning("到達済みマイルストーンを読めませんでした: " + e.getMessage());
        }
    }

    public void forget(UUID uuid) {
        known.removeIf(entry -> entry.startsWith(uuid.toString() + "/"));
    }

    public void record(UUID uuid, String key, long achievedAt, String source) {
        record(uuid, key, achievedAt, source, null);
    }

    public void record(UUID uuid, String key, long achievedAt, String source, String detail) {
        String cacheKey = cacheKey(uuid, key);
        if (!known.add(cacheKey)) {
            return;
        }
        writer.execute(() -> {
            try (PreparedStatement statement = database.prepare("""
                    INSERT INTO milestones (uuid, key, achieved_at, source, detail) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(uuid, key) DO NOTHING
                    """, uuid.toString(), key, achievedAt, source, detail)) {
                statement.executeUpdate();
            } catch (SQLException e) {
                known.remove(cacheKey);
                logger.warning("マイルストーンを書けませんでした (" + key + "): " + e.getMessage());
            }
        });
    }

    /**
     * 遡及処理から呼ぶ同期版。書き込みスレッド上で直接実行する。
     */
    public void recordDirect(UUID uuid, String key, long achievedAt, String source, String detail) throws SQLException {
        try (PreparedStatement statement = database.prepare("""
                INSERT INTO milestones (uuid, key, achieved_at, source, detail) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid, key) DO UPDATE SET achieved_at = MIN(milestones.achieved_at, excluded.achieved_at)
                """, uuid.toString(), key, achievedAt, source, detail)) {
            statement.executeUpdate();
        }
        known.add(cacheKey(uuid, key));
    }

    private static String cacheKey(UUID uuid, String key) {
        return uuid + "/" + key;
    }
}
