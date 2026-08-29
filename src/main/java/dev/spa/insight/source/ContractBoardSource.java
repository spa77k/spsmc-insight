package dev.spa.insight.source;

import dev.spa.insight.milestone.Milestones;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * ContractBoard（依頼掲示板）の SQLite を読む。
 * 依頼を出した / 受けた の初回は、経済圏に入った合図としてファネルに効く。
 */
public class ContractBoardSource implements SourceAdapter {

    private final File database;

    public ContractBoardSource(File pluginsFolder) {
        this.database = SqliteReader.findDatabase(new File(pluginsFolder, "ContractBoard"), "irai.db", "contractboard.db");
    }

    @Override
    public String id() {
        return "contractboard";
    }

    @Override
    public SourceStatus probe() {
        if (database == null) {
            return SourceStatus.unavailable(id(), "SQLiteファイルが見つかりません");
        }
        try (Connection connection = SqliteReader.openReadOnly(database)) {
            if (!SqliteReader.hasTable(connection, "requests")) {
                return SourceStatus.unavailable(id(), "requests テーブルがありません: " + database.getName());
            }
            return SourceStatus.available(id(), database.getPath());
        } catch (Exception e) {
            return SourceStatus.error(id(), e.getMessage());
        }
    }

    @Override
    public void collect(Sink sink) throws Exception {
        if (database == null) {
            return;
        }
        try (Connection connection = SqliteReader.openReadOnly(database)) {
            for (UUID uuid : sink.targets()) {
                collectOne(connection, sink, uuid);
            }
        }
    }

    private void collectOne(Connection connection, Sink sink, UUID uuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), MIN(created_at) FROM requests WHERE requester_id = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getInt(1) > 0) {
                    sink.value(uuid, "requests_posted", result.getInt(1));
                    long first = result.getLong(2);
                    sink.milestone(uuid, Milestones.FIRST_CONTRACT_POST,
                            first > 0 ? first : sink.now(), "contractboard");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), MIN(created_at) FROM requests WHERE worker_id = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getInt(1) > 0) {
                    sink.value(uuid, "requests_accepted", result.getInt(1));
                    long first = result.getLong(2);
                    sink.milestone(uuid, Milestones.FIRST_CONTRACT_ACCEPT,
                            first > 0 ? first : sink.now(), "contractboard");
                }
            }
        }
    }
}
