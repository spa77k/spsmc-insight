package dev.spa.insight.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * プレイヤーの初参加日時と累計値。コホートの起点になるので、初参加は常に古い方を残す。
 */
public class PlayerStore {

    private final Database database;

    public PlayerStore(Database database) {
        this.database = database;
    }

    public record PlayerInfo(UUID uuid, String name, long firstSeen, long lastSeen, String firstSeenSource,
                             int totalSessions, long totalPlaytimeMillis, long totalActiveMillis) {
    }

    public PlayerInfo load(UUID uuid) throws SQLException {
        try (PreparedStatement statement = database.prepare("""
                SELECT name, first_seen, last_seen, first_seen_source, total_sessions, total_playtime_ms, total_active_ms
                FROM players WHERE uuid = ?
                """, uuid.toString());
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new PlayerInfo(uuid, result.getString(1), result.getLong(2), result.getLong(3),
                    result.getString(4), result.getInt(5), result.getLong(6), result.getLong(7));
        }
    }

    /**
     * 初参加を登録する。すでに記録があれば、より古い日時を残したうえで名前を更新する。
     */
    public void seenAt(UUID uuid, String name, long at, String source) throws SQLException {
        try (PreparedStatement statement = database.prepare("""
                INSERT INTO players (uuid, name, first_seen, last_seen, first_seen_source)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = COALESCE(excluded.name, players.name),
                    first_seen = MIN(players.first_seen, excluded.first_seen),
                    last_seen = MAX(players.last_seen, excluded.last_seen),
                    first_seen_source = CASE WHEN excluded.first_seen < players.first_seen
                                             THEN excluded.first_seen_source ELSE players.first_seen_source END
                """, uuid.toString(), name, at, at, source)) {
            statement.executeUpdate();
        }
    }

    public void countSession(UUID uuid) throws SQLException {
        try (PreparedStatement statement = database.prepare(
                "UPDATE players SET total_sessions = total_sessions + 1 WHERE uuid = ?", uuid.toString())) {
            statement.executeUpdate();
        }
    }

    public void addTotals(UUID uuid, long playtimeDelta, long activeDelta, long lastSeen) throws SQLException {
        try (PreparedStatement statement = database.prepare("""
                UPDATE players SET total_playtime_ms = total_playtime_ms + ?,
                                   total_active_ms = total_active_ms + ?,
                                   last_seen = MAX(last_seen, ?)
                WHERE uuid = ?
                """, playtimeDelta, activeDelta, lastSeen, uuid.toString())) {
            statement.executeUpdate();
        }
    }

    public boolean hasPlayedBefore(UUID uuid, long beforeMillis) throws SQLException {
        try (PreparedStatement statement = database.prepare(
                "SELECT 1 FROM sessions WHERE uuid = ? AND join_at < ? LIMIT 1", uuid.toString(), beforeMillis);
             ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    public int countPreviousSessions(UUID uuid, long beforeMillis) throws SQLException {
        try (PreparedStatement statement = database.prepare(
                "SELECT COUNT(*) FROM sessions WHERE uuid = ? AND join_at < ?", uuid.toString(), beforeMillis);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getInt(1) : 0;
        }
    }
}
