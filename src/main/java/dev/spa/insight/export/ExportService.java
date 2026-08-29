package dev.spa.insight.export;

import dev.spa.insight.InsightConfig;
import dev.spa.insight.milestone.Milestones;
import dev.spa.insight.source.SourceRegistry;
import dev.spa.insight.source.SourceStatus;
import dev.spa.insight.storage.Database;
import dev.spa.insight.util.Json;
import dev.spa.insight.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * 週ごとのJSON書き出し。人が読むためではなく、まとめてAIに読ませるための形にする。
 * 大きくなりやすい明細はJSONLで種別ごとに分ける。
 */
public class ExportService {

    private final Database database;
    private final InsightConfig config;
    private final SourceRegistry sources;
    private final File dataFolder;
    private final Logger logger;

    public ExportService(Database database, InsightConfig config, SourceRegistry sources, File dataFolder,
                         Logger logger) {
        this.database = database;
        this.config = config;
        this.sources = sources;
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public record ExportResult(String week, File directory, int files, long bytes, List<String> warnings) {
    }

    public ExportResult export(String weekKey) throws SQLException, IOException {
        long now = System.currentTimeMillis();
        long from = TimeUtil.weekStartMillis(weekKey);
        long to = TimeUtil.weekEndMillis(weekKey);

        File root = new File(dataFolder, config.exportDirectory());
        File directory = new File(root, weekKey);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("出力先を作成できませんでした: " + directory.getPath());
        }

        List<String> warnings = new ArrayList<>();
        Map<String, Object> totals = new TreeMap<>();
        Map<String, Long> materialTotals = new HashMap<>();
        Map<String, Long> commandTotals = new HashMap<>();
        Set<String> activePlayers = new HashSet<>();
        Set<String> newPlayers = new HashSet<>();

        List<File> written = new ArrayList<>();
        written.add(writeSessions(directory, from, to, totals, materialTotals, commandTotals, activePlayers, newPlayers));
        written.add(writeEvents(directory, from, to));
        written.add(writeMaterials(directory, from, to));
        written.add(writeDaily(directory, weekKey));
        written.add(writePlayers(directory));
        written.add(writeMilestones(directory));

        Map<String, Object> cohorts = new CohortCalculator(database).build(now);
        written.add(writeJson(new File(directory, "cohorts.json"), cohorts));
        written.add(writeJson(new File(directory, "funnel.json"), buildFunnel(now)));
        written.add(writeJson(new File(directory, "sources.json"), buildSources(now)));

        Map<String, Object> summary = buildSummary(weekKey, from, to, now, totals, materialTotals, commandTotals,
                activePlayers, newPlayers, written, warnings);
        File summaryFile = writeJson(new File(directory, "summary.json"), summary);
        written.add(summaryFile);

        long bytes = written.stream().filter(File::isFile).mapToLong(File::length).sum();
        recordExport(weekKey, now, written.size(), bytes);
        logger.info("エクスポートしました: " + directory.getPath() + " files=" + written.size() + " bytes=" + bytes);
        return new ExportResult(weekKey, directory, written.size(), bytes, warnings);
    }

    private File writeSessions(File directory, long from, long to, Map<String, Object> totals,
                               Map<String, Long> materialTotals, Map<String, Long> commandTotals,
                               Set<String> activePlayers, Set<String> newPlayers) throws SQLException, IOException {
        Map<Long, Map<String, Object>> counters = loadCounters(from, to);
        Map<Long, Map<String, Object>> worlds = loadWorlds(from, to);
        Map<Long, List<Object>> materials = loadMaterials(from, to, materialTotals, commandTotals);

        File file = new File(directory, "sessions.jsonl");
        try (JsonLines out = new JsonLines(file);
             PreparedStatement statement = database.prepare("""
                     SELECT id, uuid, name, day, join_at, quit_at, duration_ms, active_ms, join_world, quit_world,
                            quit_reason, first_session, session_index, days_since_first, closed
                     FROM sessions WHERE join_at >= ? AND join_at < ? ORDER BY join_at
                     """, from, to);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long id = result.getLong(1);
                String uuid = result.getString(2);
                activePlayers.add(uuid);
                boolean first = result.getInt(12) == 1;
                if (first) {
                    newPlayers.add(uuid);
                }
                Map<String, Object> row = Json.obj();
                row.put("session_id", id);
                row.put("uuid", uuid);
                row.put("name", result.getString(3));
                row.put("day", result.getString(4));
                row.put("join_at", TimeUtil.stamp(result.getLong(5)));
                row.put("quit_at", TimeUtil.stamp(result.getLong(6)));
                row.put("duration_ms", result.getLong(7));
                row.put("active_ms", result.getLong(8));
                row.put("idle_ms", Math.max(0L, result.getLong(7) - result.getLong(8)));
                row.put("join_world", result.getString(9));
                row.put("quit_world", result.getString(10));
                row.put("quit_reason", result.getString(11));
                row.put("first_session", first);
                row.put("session_index", result.getInt(13));
                row.put("days_since_first", result.getLong(14));
                row.put("closed", result.getInt(15) == 1);
                row.put("counters", counters.getOrDefault(id, Map.of()));
                row.put("worlds_ms", worlds.getOrDefault(id, Map.of()));
                row.put("breakdown", materials.getOrDefault(id, List.of()));
                out.write(row);

                addTotal(totals, "sessions", 1L);
                addTotal(totals, "duration_ms", result.getLong(7));
                addTotal(totals, "active_ms", result.getLong(8));
                for (Map.Entry<String, Object> counter : counters.getOrDefault(id, Map.of()).entrySet()) {
                    addTotal(totals, counter.getKey(), ((Number) counter.getValue()).longValue());
                }
            }
        }
        return file;
    }

    private Map<Long, Map<String, Object>> loadCounters(long from, long to) throws SQLException {
        Map<Long, Map<String, Object>> bySession = new HashMap<>();
        try (PreparedStatement statement = database.prepare("""
                SELECT c.session_id, c.metric, c.value FROM session_counters c
                JOIN sessions s ON s.id = c.session_id
                WHERE s.join_at >= ? AND s.join_at < ?
                """, from, to);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                bySession.computeIfAbsent(result.getLong(1), key -> new TreeMap<>())
                        .put(result.getString(2), result.getLong(3));
            }
        }
        return bySession;
    }

    private Map<Long, Map<String, Object>> loadWorlds(long from, long to) throws SQLException {
        Map<Long, Map<String, Object>> bySession = new HashMap<>();
        try (PreparedStatement statement = database.prepare("""
                SELECT w.session_id, w.world, w.ms FROM session_worlds w
                JOIN sessions s ON s.id = w.session_id
                WHERE s.join_at >= ? AND s.join_at < ?
                """, from, to);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                bySession.computeIfAbsent(result.getLong(1), key -> new TreeMap<>())
                        .put(result.getString(2), result.getLong(3));
            }
        }
        return bySession;
    }

    private Map<Long, List<Object>> loadMaterials(long from, long to, Map<String, Long> materialTotals,
                                                  Map<String, Long> commandTotals) throws SQLException {
        Map<Long, List<Object>> bySession = new HashMap<>();
        try (PreparedStatement statement = database.prepare("""
                SELECT m.session_id, m.action, m.material, m.value FROM session_materials m
                JOIN sessions s ON s.id = m.session_id
                WHERE s.join_at >= ? AND s.join_at < ?
                """, from, to);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Map<String, Object> row = Json.obj();
                String action = result.getString(2);
                String material = result.getString(3);
                long value = result.getLong(4);
                row.put("action", action);
                row.put("key", material);
                row.put("value", value);
                bySession.computeIfAbsent(result.getLong(1), key -> new ArrayList<>()).add(row);
                if ("command".equals(action)) {
                    commandTotals.merge(material, value, Long::sum);
                } else {
                    materialTotals.merge(action + "/" + material, value, Long::sum);
                }
            }
        }
        return bySession;
    }

    private File writeEvents(File directory, long from, long to) throws SQLException, IOException {
        File file = new File(directory, "events.jsonl");
        try (JsonLines out = new JsonLines(file);
             PreparedStatement statement = database.prepare("""
                     SELECT s.uuid, s.name, s.day, s.id, c.metric, c.value, s.join_at
                     FROM session_counters c JOIN sessions s ON s.id = c.session_id
                     WHERE s.join_at >= ? AND s.join_at < ?
                     ORDER BY s.join_at, c.metric
                     """, from, to);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Map<String, Object> row = Json.obj();
                row.put("uuid", result.getString(1));
                row.put("name", result.getString(2));
                row.put("day", result.getString(3));
                row.put("session_id", result.getLong(4));
                row.put("metric", result.getString(5));
                row.put("value", result.getLong(6));
                row.put("join_at", TimeUtil.stamp(result.getLong(7)));
                out.write(row);
            }
        }
        return file;
    }

    private File writeMaterials(File directory, long from, long to) throws SQLException, IOException {
        File file = new File(directory, "breakdown.jsonl");
        try (JsonLines out = new JsonLines(file);
             PreparedStatement statement = database.prepare("""
                     SELECT s.uuid, s.name, s.day, s.id, m.action, m.material, m.value
                     FROM session_materials m JOIN sessions s ON s.id = m.session_id
                     WHERE s.join_at >= ? AND s.join_at < ?
                     ORDER BY s.join_at, m.action, m.value DESC
                     """, from, to);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Map<String, Object> row = Json.obj();
                row.put("uuid", result.getString(1));
                row.put("name", result.getString(2));
                row.put("day", result.getString(3));
                row.put("session_id", result.getLong(4));
                row.put("action", result.getString(5));
                row.put("key", result.getString(6));
                row.put("value", result.getLong(7));
                out.write(row);
            }
        }
        return file;
    }

    private File writeDaily(File directory, String weekKey) throws SQLException, IOException {
        String fromDay = TimeUtil.dayKey(TimeUtil.weekStartDate(weekKey));
        String toDay = TimeUtil.dayKey(TimeUtil.weekStartDate(weekKey).plusDays(6));
        File file = new File(directory, "daily.jsonl");
        try (JsonLines out = new JsonLines(file);
             PreparedStatement statement = database.prepare("""
                     SELECT d.uuid, p.name, d.day, d.sessions, d.playtime_ms, d.active_ms, d.source, p.first_seen
                     FROM daily_activity d LEFT JOIN players p ON p.uuid = d.uuid
                     WHERE d.day >= ? AND d.day <= ?
                     ORDER BY d.day, d.uuid
                     """, fromDay, toDay);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Map<String, Object> row = Json.obj();
                row.put("uuid", result.getString(1));
                row.put("name", result.getString(2));
                row.put("day", result.getString(3));
                row.put("sessions", result.getInt(4));
                row.put("playtime_ms", result.getLong(5));
                row.put("active_ms", result.getLong(6));
                row.put("source", result.getString(7));
                long firstSeen = result.getLong(8);
                row.put("days_since_first", firstSeen > 0
                        ? TimeUtil.daysBetween(TimeUtil.dayKey(firstSeen), result.getString(3))
                        : null);
                out.write(row);
            }
        }
        return file;
    }

    private File writePlayers(File directory) throws SQLException, IOException {
        Map<String, Map<String, Object>> snapshots = new HashMap<>();
        try (PreparedStatement statement = database.prepare(
                "SELECT uuid, source, key, value FROM external_snapshots ORDER BY uuid, source, key");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                snapshots.computeIfAbsent(result.getString(1), key -> new TreeMap<>())
                        .put(result.getString(2) + "." + result.getString(3), result.getString(4));
            }
        }

        File file = new File(directory, "players.jsonl");
        try (JsonLines out = new JsonLines(file);
             PreparedStatement statement = database.prepare("""
                     SELECT uuid, name, first_seen, last_seen, first_seen_source, total_sessions,
                            total_playtime_ms, total_active_ms
                     FROM players ORDER BY first_seen
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String uuid = result.getString(1);
                long firstSeen = result.getLong(3);
                Map<String, Object> row = Json.obj();
                row.put("uuid", uuid);
                row.put("name", result.getString(2));
                row.put("first_seen", TimeUtil.stamp(firstSeen));
                row.put("first_day", TimeUtil.dayKey(firstSeen));
                row.put("cohort_week", TimeUtil.weekKey(firstSeen));
                row.put("last_seen", TimeUtil.stamp(result.getLong(4)));
                row.put("first_seen_source", result.getString(5));
                row.put("total_sessions", result.getInt(6));
                row.put("total_playtime_ms", result.getLong(7));
                row.put("total_active_ms", result.getLong(8));
                row.put("external", snapshots.getOrDefault(uuid, Map.of()));
                out.write(row);
            }
        }
        return file;
    }

    private File writeMilestones(File directory) throws SQLException, IOException {
        File file = new File(directory, "milestones.jsonl");
        try (JsonLines out = new JsonLines(file);
             PreparedStatement statement = database.prepare("""
                     SELECT m.uuid, p.name, m.key, m.achieved_at, m.source, m.detail, p.first_seen
                     FROM milestones m LEFT JOIN players p ON p.uuid = m.uuid
                     ORDER BY m.uuid, m.achieved_at
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long achievedAt = result.getLong(4);
                long firstSeen = result.getLong(7);
                Map<String, Object> row = Json.obj();
                row.put("uuid", result.getString(1));
                row.put("name", result.getString(2));
                row.put("milestone", result.getString(3));
                row.put("achieved_at", TimeUtil.stamp(achievedAt));
                row.put("source", result.getString(5));
                row.put("detail", result.getString(6));
                row.put("minutes_from_first_join", firstSeen > 0 && achievedAt >= firstSeen
                        ? (achievedAt - firstSeen) / 60000L
                        : null);
                out.write(row);
            }
        }
        return file;
    }

    /**
     * 到達者数の並び。どの段階で人数が落ちるかを1ファイルで見えるようにする。
     */
    private Map<String, Object> buildFunnel(long now) throws SQLException {
        Map<String, Integer> reached = new HashMap<>();
        try (PreparedStatement statement = database.prepare(
                "SELECT key, COUNT(DISTINCT uuid) FROM milestones GROUP BY key");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                reached.put(result.getString(1), result.getInt(2));
            }
        }
        Map<String, Map<String, Integer>> byCohort = new TreeMap<>();
        try (PreparedStatement statement = database.prepare("""
                SELECT p.first_seen, m.key, COUNT(DISTINCT m.uuid)
                FROM milestones m JOIN players p ON p.uuid = m.uuid
                GROUP BY p.first_seen, m.key
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String week = TimeUtil.weekKey(result.getLong(1));
                byCohort.computeIfAbsent(week, key -> new TreeMap<>())
                        .merge(result.getString(2), result.getInt(3), Integer::sum);
            }
        }
        long players = database.queryLong("SELECT COUNT(*) FROM players");

        List<Object> steps = new ArrayList<>();
        int previous = -1;
        for (String key : Milestones.FUNNEL_ORDER) {
            int count = reached.getOrDefault(key, 0);
            Map<String, Object> step = Json.obj();
            step.put("milestone", key);
            step.put("reached", count);
            step.put("reach_rate", players == 0 ? 0.0 : Math.round((double) count / players * 10000.0) / 10000.0);
            step.put("drop_from_previous", previous < 0 ? null : previous - count);
            steps.add(step);
            previous = count;
        }

        Map<String, Object> root = Json.obj();
        root.put("generated_at", TimeUtil.stamp(now));
        root.put("player_count", players);
        root.put("note", "reached は到達した実人数。並びは想定する体験順であり、実際の順序を保証しない");
        root.put("steps", steps);
        root.put("by_cohort", byCohort);
        return root;
    }

    private Map<String, Object> buildSources(long now) throws SQLException {
        Map<String, Object> root = Json.obj();
        root.put("generated_at", TimeUtil.stamp(now));
        Map<String, Object> statuses = new LinkedHashMap<>();
        for (Map.Entry<String, SourceStatus> entry : sources.statuses().entrySet()) {
            Map<String, Object> node = Json.obj();
            node.put("status", entry.getValue().status());
            node.put("detail", entry.getValue().detail());
            statuses.put(entry.getKey(), node);
        }
        root.put("sources", statuses);
        Map<String, Object> stored = Json.obj();
        try (PreparedStatement statement = database.prepare(
                "SELECT source, status, detail, checked_at FROM source_status ORDER BY source");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Map<String, Object> node = Json.obj();
                node.put("status", result.getString(2));
                node.put("detail", result.getString(3));
                node.put("checked_at", TimeUtil.stamp(result.getLong(4)));
                stored.put(result.getString(1), node);
            }
        }
        root.put("last_checked", stored);
        return root;
    }

    private Map<String, Object> buildSummary(String weekKey, long from, long to, long now, Map<String, Object> totals,
                                             Map<String, Long> materialTotals, Map<String, Long> commandTotals,
                                             Set<String> activePlayers, Set<String> newPlayers, List<File> written,
                                             List<String> warnings) {
        Map<String, Object> root = Json.obj();
        root.put("week", weekKey);
        root.put("window_start", TimeUtil.stamp(from));
        root.put("window_end", TimeUtil.stamp(to));
        root.put("timezone", TimeUtil.ZONE.getId());
        root.put("generated_at", TimeUtil.stamp(now));
        root.put("active_players", activePlayers.size());
        root.put("new_players", newPlayers.size());
        root.put("returning_players", Math.max(0, activePlayers.size() - newPlayers.size()));
        root.put("totals", totals);
        root.put("top_breakdown", top(materialTotals, 30));
        root.put("top_commands", top(commandTotals, 30));

        List<Object> files = new ArrayList<>();
        for (File file : written) {
            if (!file.isFile()) {
                continue;
            }
            Map<String, Object> node = Json.obj();
            node.put("name", file.getName());
            node.put("bytes", file.length());
            files.add(node);
            if (file.length() > config.fileSizeWarnBytes()) {
                warnings.add(file.getName() + " が "
                        + (file.length() / 1024 / 1024) + "MB あります。AIに渡すときは分割してください");
            }
        }
        root.put("files", files);
        root.put("warnings", warnings);
        root.put("reading_order", List.of("summary.json", "cohorts.json", "funnel.json", "daily.jsonl",
                "players.jsonl", "milestones.jsonl", "sessions.jsonl", "events.jsonl", "breakdown.jsonl",
                "sources.json"));
        return root;
    }

    private static List<Object> top(Map<String, Long> values, int limit) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> {
                    Map<String, Object> node = Json.obj();
                    node.put("key", entry.getKey());
                    node.put("value", entry.getValue());
                    return (Object) node;
                })
                .toList();
    }

    private static void addTotal(Map<String, Object> totals, String key, long amount) {
        Object current = totals.get(key);
        long base = current instanceof Number number ? number.longValue() : 0L;
        totals.put(key, base + amount);
    }

    private File writeJson(File file, Map<String, Object> content) throws IOException {
        Files.writeString(file.toPath(), Json.writePretty(content), StandardCharsets.UTF_8);
        return file;
    }

    private void recordExport(String weekKey, long now, int files, long bytes) throws SQLException {
        try (PreparedStatement statement = database.prepare("""
                INSERT INTO exports (week, generated_at, files, bytes) VALUES (?, ?, ?, ?)
                ON CONFLICT(week) DO UPDATE SET generated_at = excluded.generated_at,
                                                files = excluded.files,
                                                bytes = excluded.bytes
                """, weekKey, now, files, bytes)) {
            statement.executeUpdate();
        }
    }
}
