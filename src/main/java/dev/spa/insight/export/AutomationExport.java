package dev.spa.insight.export;

import dev.spa.insight.util.Json;
import dev.spa.insight.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

/**
 * EcoLifeAssist が残した自動化装置の検出記録（plugins/EcoLifeAssist/automation.db）を週次の出力に含める。
 * 読み取り専用で開き、書き込みは行わない。列名は EcoLifeAssist の AutomationStore と揃えている。
 */
public final class AutomationExport {

    private static final String[] KINDS = {"transfer", "pickup", "piston", "dispense", "mob_death"};

    private AutomationExport() {
    }

    /** その週の明細を automation.jsonl に書き、summary.json 用の集計を返す。 */
    public static Map<String, Object> write(File pluginsFolder, File directory, long from, long to)
            throws IOException {
        Map<String, Object> summary = Json.obj();
        File database = new File(new File(pluginsFolder, "EcoLifeAssist"), "automation.db");
        if (!database.isFile()) {
            summary.put("available", false);
            summary.put("reason", "automation.db がありません（EcoLifeAssist の検出が無効、または未検出）");
            return summary;
        }
        File file = new File(directory, "automation.jsonl");
        int week = 0;
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:file:" + database.getAbsolutePath() + "?mode=ro");
             JsonLines out = new JsonLines(file)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT detected_at, world, chunk_x, chunk_z, x, y, z, owner, placer, placed_at, map_url,"
                            + " transfer, pickup, piston, dispense, mob_death, sent FROM detections"
                            + " WHERE detected_at >= ? AND detected_at < ? ORDER BY detected_at")) {
                statement.setLong(1, from);
                statement.setLong(2, to);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Map<String, Object> row = Json.obj();
                        row.put("detected_at", TimeUtil.stamp(result.getLong(1)));
                        row.put("world", result.getString(2));
                        row.put("chunk_x", result.getInt(3));
                        row.put("chunk_z", result.getInt(4));
                        row.put("x", result.getInt(5));
                        row.put("y", result.getInt(6));
                        row.put("z", result.getInt(7));
                        row.put("claim_owner", emptyToNull(result.getString(8)));
                        row.put("placer", emptyToNull(result.getString(9)));
                        long placedAt = result.getLong(10);
                        row.put("placed_at", placedAt > 0 ? TimeUtil.stamp(placedAt * 1000L) : null);
                        row.put("map_url", emptyToNull(result.getString(11)));
                        Map<String, Object> counts = Json.obj();
                        for (int i = 0; i < KINDS.length; i++) {
                            counts.put(KINDS[i], result.getInt(12 + i));
                        }
                        row.put("counts", counts);
                        row.put("notified", result.getInt(17) == 1);
                        out.write(row);
                        week++;
                    }
                }
            }
            summary.put("available", true);
            summary.put("detections_this_week", week);
            summary.put("detections_total", count(connection));
            // 通知は1場所1回なので、同じ人の名前が複数の場所で出ていれば繰り返し作っている疑いがある
            summary.put("repeat_claim_owners", repeats(connection, "owner"));
            summary.put("repeat_placers", repeats(connection, "placer"));
        } catch (SQLException e) {
            summary.put("available", false);
            summary.put("reason", "automation.db を読めませんでした: " + e.getMessage());
        }
        summary.put("file", file.isFile() ? file.getName() : null);
        summary.put("note", "通知は運営が確認するための候補。違反の確定ではない。");
        return summary;
    }

    private static long count(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM detections");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private static Map<String, Object> repeats(Connection connection, String column) throws SQLException {
        Map<String, Object> names = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + ", COUNT(*) FROM detections WHERE " + column + " <> ''"
                        + " GROUP BY " + column + " HAVING COUNT(*) >= 2 ORDER BY COUNT(*) DESC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                names.put(result.getString(1), result.getLong(2));
            }
        }
        return names;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
