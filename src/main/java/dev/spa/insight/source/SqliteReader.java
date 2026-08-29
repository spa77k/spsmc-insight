package dev.spa.insight.source;

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
        return DriverManager.getConnection("jdbc:sqlite:file:" + file.getAbsolutePath() + "?mode=ro&immutable=1");
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
