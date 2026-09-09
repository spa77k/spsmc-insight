package dev.spa.insight.storage;

import org.bukkit.plugin.Plugin;
import dev.spa.insight.jobs.JobsAuditStore;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 収集したデータの正本。書き込みは常に非同期スレッドから1本で行う。
 */
public class Database {

    private static final int SCHEMA_VERSION = 2;

    private final Plugin plugin;
    private Connection connection;

    public Database(Plugin plugin) {
        this.plugin = plugin;
    }

    public void connect() throws SQLException {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLiteドライバを読み込めませんでした", e);
        }
        File dbFile = new File(dataFolder, "insight.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        createTables();
        setMeta("schema_version", String.valueOf(SCHEMA_VERSION));
    }

    public Connection connection() {
        return connection;
    }

    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("データベースを閉じられませんでした: " + e.getMessage());
        } finally {
            connection = null;
        }
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid TEXT PRIMARY KEY,
                    name TEXT,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    first_seen_source TEXT NOT NULL,
                    total_sessions INTEGER NOT NULL DEFAULT 0,
                    total_playtime_ms INTEGER NOT NULL DEFAULT 0,
                    total_active_ms INTEGER NOT NULL DEFAULT 0
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    name TEXT,
                    day TEXT NOT NULL,
                    join_at INTEGER NOT NULL,
                    quit_at INTEGER,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    active_ms INTEGER NOT NULL DEFAULT 0,
                    join_world TEXT,
                    quit_world TEXT,
                    quit_reason TEXT,
                    first_session INTEGER NOT NULL DEFAULT 0,
                    session_index INTEGER NOT NULL DEFAULT 0,
                    days_since_first INTEGER NOT NULL DEFAULT 0,
                    closed INTEGER NOT NULL DEFAULT 0
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS session_counters (
                    session_id INTEGER NOT NULL,
                    metric TEXT NOT NULL,
                    value INTEGER NOT NULL,
                    PRIMARY KEY (session_id, metric)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS session_worlds (
                    session_id INTEGER NOT NULL,
                    world TEXT NOT NULL,
                    ms INTEGER NOT NULL,
                    PRIMARY KEY (session_id, world)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS session_materials (
                    session_id INTEGER NOT NULL,
                    action TEXT NOT NULL,
                    material TEXT NOT NULL,
                    value INTEGER NOT NULL,
                    PRIMARY KEY (session_id, action, material)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS daily_activity (
                    uuid TEXT NOT NULL,
                    day TEXT NOT NULL,
                    sessions INTEGER NOT NULL DEFAULT 0,
                    playtime_ms INTEGER NOT NULL DEFAULT 0,
                    active_ms INTEGER NOT NULL DEFAULT 0,
                    source TEXT NOT NULL DEFAULT 'live',
                    PRIMARY KEY (uuid, day)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS daily_rollup (
                    uuid TEXT NOT NULL,
                    day TEXT NOT NULL,
                    metric TEXT NOT NULL,
                    value INTEGER NOT NULL,
                    PRIMARY KEY (uuid, day, metric)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS milestones (
                    uuid TEXT NOT NULL,
                    key TEXT NOT NULL,
                    achieved_at INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    detail TEXT,
                    PRIMARY KEY (uuid, key)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS external_snapshots (
                    uuid TEXT NOT NULL,
                    source TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT,
                    taken_at INTEGER NOT NULL,
                    PRIMARY KEY (uuid, source, key)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS source_status (
                    source TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    detail TEXT,
                    checked_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS exports (
                    week TEXT PRIMARY KEY,
                    generated_at INTEGER NOT NULL,
                    files INTEGER NOT NULL,
                    bytes INTEGER NOT NULL
                )
                """);
            JobsAuditStore.migrate(statement);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sessions_uuid ON sessions (uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sessions_join ON sessions (join_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sessions_day ON sessions (day)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_daily_day ON daily_activity (day)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_milestones_key ON milestones (key)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_players_first ON players (first_seen)");
        }
    }

    public String getMeta(String key, String fallback) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getString(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("metaを読めませんでした (" + key + "): " + e.getMessage());
        }
        return fallback;
    }

    public void setMeta(String key, String value) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("metaを書けませんでした (" + key + "): " + e.getMessage());
        }
    }

    public void recordSourceStatus(String source, String status, String detail) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO source_status (source, status, detail, checked_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(source) DO UPDATE SET status = excluded.status,
                                                  detail = excluded.detail,
                                                  checked_at = excluded.checked_at
                """)) {
            statement.setString(1, source);
            statement.setString(2, status);
            statement.setString(3, detail);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("ソース状態を書けませんでした (" + source + "): " + e.getMessage());
        }
    }

    public long queryLong(String sql, Object... parameters) {
        try (PreparedStatement statement = prepare(sql, parameters);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        } catch (SQLException e) {
            plugin.getLogger().warning("集計に失敗しました: " + e.getMessage());
            return 0L;
        }
    }

    public PreparedStatement prepare(String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
        return statement;
    }

    public void inTransaction(SqlAction action) throws SQLException {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            action.run(connection);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    @FunctionalInterface
    public interface SqlAction {
        void run(Connection connection) throws SQLException;
    }
}
