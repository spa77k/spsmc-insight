package dev.spa.insight.backfill;

import dev.spa.insight.source.SqliteReader;
import dev.spa.insight.util.TimeUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CoreProtect のログから、プラグイン導入前の活動日を復元する。
 * co_session（ログイン・ログアウト）があればそれを使い、無ければ co_block の操作日で代用する。
 */
public class CoreProtectBackfill {

    /**
     * JSTの日付境界に合わせるための秒数。CoreProtect の time はUTC基準のUNIX秒。
     */
    private static final int JST_OFFSET_SECONDS = 32400;

    private final File databaseFile;

    public CoreProtectBackfill(File databaseFile) {
        this.databaseFile = databaseFile;
    }

    @FunctionalInterface
    public interface DayConsumer {
        void accept(UUID uuid, String dayKey, int sessions, long playtimeMillis) throws SQLException;
    }

    public record Result(int users, int days, String basis) {
    }

    public boolean usable() {
        return databaseFile != null && databaseFile.isFile();
    }

    public Result run(DayConsumer consumer) throws SQLException {
        try (Connection connection = SqliteReader.openReadOnly(databaseFile)) {
            Map<Integer, UUID> users = loadUsers(connection);
            if (users.isEmpty()) {
                return new Result(0, 0, "co_user が空です");
            }
            if (SqliteReader.hasTable(connection, "co_session")) {
                int days = readSessions(connection, users, consumer);
                return new Result(users.size(), days, "co_session");
            }
            if (SqliteReader.hasTable(connection, "co_block")) {
                int days = readBlocks(connection, users, consumer);
                return new Result(users.size(), days, "co_block");
            }
            return new Result(users.size(), 0, "利用できるテーブルがありません");
        }
    }

    /**
     * co_user の rowid とUUIDの対応。UUID列が無い古い版では復元できないので空で返す。
     */
    private Map<Integer, UUID> loadUsers(Connection connection) throws SQLException {
        Map<Integer, UUID> users = new HashMap<>();
        List<String> columns = SqliteReader.columns(connection, "co_user");
        boolean hasUuid = columns.stream().anyMatch(name -> name.equalsIgnoreCase("uuid"));
        if (!hasUuid) {
            return users;
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT rowid, uuid FROM co_user WHERE uuid IS NOT NULL")) {
            while (result.next()) {
                UUID uuid = parseUuid(result.getString(2));
                if (uuid != null) {
                    users.put(result.getInt(1), uuid);
                }
            }
        }
        return users;
    }

    private int readSessions(Connection connection, Map<Integer, UUID> users, DayConsumer consumer)
            throws SQLException {
        String sql = """
                SELECT user, (time + %d) / 86400 AS jst_day, MIN(time), MAX(time),
                       SUM(CASE WHEN action = 1 THEN 1 ELSE 0 END)
                FROM co_session
                GROUP BY user, jst_day
                """.formatted(JST_OFFSET_SECONDS);
        int days = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                UUID uuid = users.get(result.getInt(1));
                if (uuid == null) {
                    continue;
                }
                String dayKey = TimeUtil.dayKey(LocalDate.ofEpochDay(result.getLong(2)));
                long span = Math.max(0L, result.getLong(4) - result.getLong(3)) * 1000L;
                int sessions = Math.max(1, result.getInt(5));
                consumer.accept(uuid, dayKey, sessions, span);
                days++;
            }
        }
        return days;
    }

    /**
     * ログイン記録が無い場合の代替。操作があった日を活動日とみなす。滞在時間は推定しない。
     */
    private int readBlocks(Connection connection, Map<Integer, UUID> users, DayConsumer consumer)
            throws SQLException {
        String sql = """
                SELECT user, (time + %d) / 86400 AS jst_day, MIN(time), MAX(time)
                FROM co_block
                GROUP BY user, jst_day
                """.formatted(JST_OFFSET_SECONDS);
        int days = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                UUID uuid = users.get(result.getInt(1));
                if (uuid == null) {
                    continue;
                }
                String dayKey = TimeUtil.dayKey(LocalDate.ofEpochDay(result.getLong(2)));
                long span = Math.max(0L, result.getLong(4) - result.getLong(3)) * 1000L;
                consumer.accept(uuid, dayKey, 1, span);
                days++;
            }
        }
        return days;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
