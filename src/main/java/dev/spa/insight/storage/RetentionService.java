package dev.spa.insight.storage;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * 保持期間を過ぎた明細を日次集計へ畳んでから消す。
 * 集計値（daily_activity / daily_rollup / milestones）は消さないので、コホート分析は残る。
 */
public class RetentionService {

    private final Database database;
    private final Logger logger;

    public RetentionService(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    public record Result(int rolledUp, int removedSessions) {
    }

    public Result compress(int detailDays) throws SQLException {
        long threshold = System.currentTimeMillis() - detailDays * 86_400_000L;
        long remaining = database.queryLong("SELECT COUNT(*) FROM sessions WHERE join_at < ?", threshold);
        if (remaining == 0L) {
            return new Result(0, 0);
        }

        int[] counts = new int[2];
        database.inTransaction(connection -> {
            try (PreparedStatement statement = database.prepare("""
                    INSERT INTO daily_rollup (uuid, day, metric, value)
                    SELECT s.uuid, s.day, c.metric, SUM(c.value)
                    FROM sessions s JOIN session_counters c ON c.session_id = s.id
                    WHERE s.join_at < ?
                    GROUP BY s.uuid, s.day, c.metric
                    ON CONFLICT(uuid, day, metric) DO UPDATE SET value = daily_rollup.value + excluded.value
                    """, threshold)) {
                counts[0] += statement.executeUpdate();
            }
            try (PreparedStatement statement = database.prepare("""
                    INSERT INTO daily_rollup (uuid, day, metric, value)
                    SELECT s.uuid, s.day, 'duration_ms', SUM(s.duration_ms)
                    FROM sessions s WHERE s.join_at < ? GROUP BY s.uuid, s.day
                    ON CONFLICT(uuid, day, metric) DO UPDATE SET value = daily_rollup.value + excluded.value
                    """, threshold)) {
                counts[0] += statement.executeUpdate();
            }
            try (PreparedStatement statement = database.prepare("""
                    DELETE FROM session_counters WHERE session_id IN
                        (SELECT id FROM sessions WHERE join_at < ?)
                    """, threshold)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = database.prepare("""
                    DELETE FROM session_worlds WHERE session_id IN
                        (SELECT id FROM sessions WHERE join_at < ?)
                    """, threshold)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = database.prepare("""
                    DELETE FROM session_materials WHERE session_id IN
                        (SELECT id FROM sessions WHERE join_at < ?)
                    """, threshold)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = database.prepare("DELETE FROM sessions WHERE join_at < ?", threshold)) {
                counts[1] += statement.executeUpdate();
            }
        });

        logger.info("保持期間を超えた明細を圧縮しました: rollup=" + counts[0] + " sessions=" + counts[1]);
        return new Result(counts[0], counts[1]);
    }
}
