package dev.spa.insight.source;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * CoreProtect の SQLite。過去180日のブロック操作が入っており、遡及の主軸になる。
 * 毎分の取り込みには重すぎるため、collect では何もせず、遡及からのみ読む。
 */
public class CoreProtectSource implements SourceAdapter {

    private final File database;

    public CoreProtectSource(File pluginsFolder) {
        this.database = SqliteReader.findDatabase(new File(pluginsFolder, "CoreProtect"), "database.db");
    }

    @Override
    public String id() {
        return "coreprotect";
    }

    public File databaseFile() {
        return database;
    }

    @Override
    public SourceStatus probe() {
        if (database == null) {
            return SourceStatus.unavailable(id(), "database.db が見つかりません");
        }
        try (Connection connection = SqliteReader.openReadOnly(database)) {
            if (!SqliteReader.hasTable(connection, "co_user")) {
                return SourceStatus.unavailable(id(), "co_user テーブルがありません");
            }
            long users = 0L;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM co_user")) {
                if (result.next()) {
                    users = result.getLong(1);
                }
            }
            return SourceStatus.available(id(), database.getPath() + " users=" + users);
        } catch (Exception e) {
            return SourceStatus.error(id(), e.getMessage());
        }
    }

    @Override
    public void collect(Sink sink) {
        // 定期取り込みでは読まない。遡及（BackfillService）からのみ使う。
    }
}
