package dev.spa.insight.source;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteException;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 他プラグインのSQLiteを読み取り専用で開く。書き込みは絶対に行わない。
 */
public final class SqliteReader {

    private SqliteReader() {
    }

    public static Connection openReadOnly(File file) throws SQLException {
        if (file == null || !file.isFile()) {
            throw new SQLException("ファイルがありません: " + file);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(1000);
        // 稼働中のDBは変更されるため immutable / nolock は使わない。
        // SQLite本来のロックとWAL読み取りを保ち、認証元のDB内容は変更しない。
        // URIとしてエスケープし、パス内の ? / # / % を接続オプションと誤認させない。
        return DriverManager.getConnection("jdbc:sqlite:" + file.toURI().toASCIIString() + "?mode=ro",
                config.toProperties());
    }

    /** 他のDBエラーを再試行で隠さず、SQLiteの一時的なロック競合だけを識別する。 */
    public static boolean isTransientLock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLiteException sqlite) {
                int primaryCode = sqlite.getResultCode().code & 0xff;
                if (primaryCode == 5 || primaryCode == 6) { // SQLITE_BUSY / SQLITE_LOCKED
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * plugins/&lt;plugin&gt;/ から拡張子がSQLiteらしいファイルを探す。名前はプラグインごとに違うため決め打ちにしない。
     */
    public static File findDatabase(File pluginFolder, String... preferredNames) {
        if (pluginFolder == null || !pluginFolder.isDirectory()) {
            return null;
        }
        for (String name : preferredNames) {
            File candidate = new File(pluginFolder, name);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        File[] files = pluginFolder.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            String lowered = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (file.isFile() && (lowered.endsWith(".db") || lowered.endsWith(".sqlite") || lowered.endsWith(".sqlite.db"))) {
                return file;
            }
        }
        return null;
    }

    public static List<String> tables(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet result = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    public static boolean hasTable(Connection connection, String table) throws SQLException {
        for (String name : tables(connection)) {
            if (name.equalsIgnoreCase(table)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> columns(Connection connection, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, "%")) {
            while (result.next()) {
                names.add(result.getString("COLUMN_NAME"));
            }
        }
        return names;
    }
}
