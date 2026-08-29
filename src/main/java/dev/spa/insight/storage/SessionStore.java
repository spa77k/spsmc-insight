package dev.spa.insight.storage;

import dev.spa.insight.model.LiveSession;
import dev.spa.insight.util.TimeUtil;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * セッション行と、それにぶら下がるカウンタの書き込み。
 * 定期フラッシュで上書き更新するので、途中で落ちても最後のフラッシュまでは残る。
 */
public class SessionStore {

    private final Database database;
    private final int breakdownTop;

    public SessionStore(Database database, int breakdownTop) {
        this.database = database;
        this.breakdownTop = breakdownTop;
    }

    public long insert(LiveSession session) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO sessions (uuid, name, day, join_at, quit_at, duration_ms, active_ms,
                                      join_world, quit_world, quit_reason, first_session, session_index,
                                      days_since_first, closed)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, NULL, NULL, ?, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, session.uuid().toString());
            statement.setString(2, session.name());
            statement.setString(3, session.day());
            statement.setLong(4, session.joinAt());
            statement.setLong(5, session.joinAt());
            statement.setString(6, session.joinWorld());
            statement.setInt(7, session.firstSession() ? 1 : 0);
            statement.setInt(8, session.sessionIndex());
            statement.setLong(9, session.daysSinceFirst());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    /**
     * 途中経過の書き出し。接続中でも呼ばれるため、常に上書き更新にする。
     */
    public void flush(LiveSession session, long now) throws SQLException {
        long id = session.sessionId();
        if (id < 0) {
            return;
        }
        session.accumulateWorld(now);
        long activeDelta = session.takeActiveDelta();
        long durationDelta = session.takeDurationDelta(now);
        database.inTransaction(connection -> {
            try (PreparedStatement statement = database.prepare("""
                    UPDATE sessions SET quit_at = ?, duration_ms = ?, active_ms = ?, quit_world = ?
                    WHERE id = ?
                    """, now, now - session.joinAt(), session.activeMillis(), session.currentWorld(), id)) {
                statement.executeUpdate();
            }
            writeCounters(id, session.counters());
            writeWorlds(id, session.worldMillis());
            writeBreakdown(id, session.breakdown());
            addDailyActivity(session.uuid(), TimeUtil.dayKey(now), 0, durationDelta, activeDelta);
        });
    }

    public void finish(LiveSession session, long now, String reason) throws SQLException {
        long id = session.sessionId();
        if (id < 0) {
            return;
        }
        flush(session, now);
        try (PreparedStatement statement = database.prepare(
                "UPDATE sessions SET closed = 1, quit_reason = ?, quit_at = ? WHERE id = ?", reason, now, id)) {
            statement.executeUpdate();
        }
    }

    private void writeCounters(long id, Map<String, AtomicLong> counters) throws SQLException {
        if (counters.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO session_counters (session_id, metric, value) VALUES (?, ?, ?)
                ON CONFLICT(session_id, metric) DO UPDATE SET value = excluded.value
                """)) {
            for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
                statement.setLong(1, id);
                statement.setString(2, entry.getKey());
                statement.setLong(3, entry.getValue().get());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void writeWorlds(long id, Map<String, AtomicLong> worlds) throws SQLException {
        if (worlds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO session_worlds (session_id, world, ms) VALUES (?, ?, ?)
                ON CONFLICT(session_id, world) DO UPDATE SET ms = excluded.ms
                """)) {
            for (Map.Entry<String, AtomicLong> entry : worlds.entrySet()) {
                statement.setLong(1, id);
                statement.setString(2, entry.getKey());
                statement.setLong(3, entry.getValue().get());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void writeBreakdown(long id, Map<String, AtomicLong> breakdown) throws SQLException {
        if (breakdown.isEmpty() || breakdownTop == 0) {
            return;
        }
        List<Map.Entry<String, AtomicLong>> top = breakdown.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, AtomicLong> entry) -> entry.getValue().get()).reversed())
                .limit(breakdownTop)
                .toList();
        try (PreparedStatement statement = database.connection().prepareStatement("""
                INSERT INTO session_materials (session_id, action, material, value) VALUES (?, ?, ?, ?)
                ON CONFLICT(session_id, action, material) DO UPDATE SET value = excluded.value
                """)) {
            for (Map.Entry<String, AtomicLong> entry : top) {
                String composite = entry.getKey();
                int slash = composite.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                statement.setLong(1, id);
                statement.setString(2, composite.substring(0, slash));
                statement.setString(3, composite.substring(slash + 1));
                statement.setLong(4, entry.getValue().get());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void addDailyActivity(UUID uuid, String day, int sessions, long playtimeDelta, long activeDelta)
            throws SQLException {
        try (PreparedStatement statement = database.prepare("""
                INSERT INTO daily_activity (uuid, day, sessions, playtime_ms, active_ms, source)
                VALUES (?, ?, ?, ?, ?, 'live')
                ON CONFLICT(uuid, day) DO UPDATE SET
                    sessions = daily_activity.sessions + excluded.sessions,
                    playtime_ms = daily_activity.playtime_ms + excluded.playtime_ms,
                    active_ms = daily_activity.active_ms + excluded.active_ms,
                    source = 'live'
                """, uuid.toString(), day, sessions, playtimeDelta, activeDelta)) {
            statement.executeUpdate();
        }
    }

    /**
     * 前回の停止・クラッシュで開いたままのセッションを閉じる。
     */
    public int closeOrphans() throws SQLException {
        try (PreparedStatement statement = database.prepare("""
                UPDATE sessions SET closed = 1, quit_reason = 'server_stop_or_crash'
                WHERE closed = 0
                """)) {
            return statement.executeUpdate();
        }
    }
}
