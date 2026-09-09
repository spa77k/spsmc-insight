package dev.spa.insight.jobs;

import dev.spa.insight.export.JsonLines;
import dev.spa.insight.storage.Database;
import dev.spa.insight.util.Json;
import dev.spa.insight.util.TimeUtil;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.*;

public final class JobsAuditExport {
    private JobsAuditExport() {}
    public static List<File> write(Database db, File directory, long from, long to) throws SQLException, IOException {
        File detail = new File(directory, "jobs-audit.jsonl");
        Map<String, Long> counts = new TreeMap<>();
        Map<String, BigDecimal> income = new TreeMap<>();
        Map<String, BigDecimal> jobPlanned = new TreeMap<>();
        try (JsonLines out = new JsonLines(detail);
             var s = db.prepare("SELECT " + JobsAuditStore.COLUMNS
                     + " FROM jobs_audit WHERE occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at,id", from, to);
             var rs = s.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = JobsAuditStore.row(rs);
                out.write(row);
                counts.merge(row.get("phase") + "/" + row.get("status"), 1L, Long::sum);
                if ("balance".equals(row.get("phase")) && "balance_verified".equals(row.get("status"))
                        && row.get("verified_delta") != null)
                    income.merge((String) row.get("uuid"), new BigDecimal((String) row.get("verified_delta")), BigDecimal::add);
                if ("pre_payment".equals(row.get("phase")) && row.get("planned_money") != null)
                    jobPlanned.merge(Objects.toString(row.get("job"), "unknown"),
                            new BigDecimal((String) row.get("planned_money")), BigDecimal::add);
            }
        }
        List<Object> players = new ArrayList<>();
        for (var entry : income.entrySet()) {
            long ms = db.queryLong("SELECT COALESCE(SUM(playtime_ms),0) FROM daily_activity WHERE uuid=? AND day>=? AND day<?",
                    entry.getKey(), TimeUtil.dayKey(from), TimeUtil.dayKey(to));
            Map<String, Object> player = Json.obj();
            player.put("uuid", entry.getKey());
            player.put("verified_net_balance_delta", entry.getValue());
            player.put("player_playtime_ms", ms);
            player.put("verified_coins_per_player_hour", ms <= 0 ? null : entry.getValue()
                    .multiply(BigDecimal.valueOf(3_600_000)).divide(BigDecimal.valueOf(ms), 6, RoundingMode.HALF_UP));
            players.add(player);
        }
        Map<String, Object> summary = Json.obj();
        summary.put("window_start", TimeUtil.stamp(from));
        summary.put("window_end", TimeUtil.stamp(to));
        summary.put("counts", counts);
        summary.put("players", players);
        summary.put("planned_money_by_job", jobPlanned);
        summary.put("income_by_job", null);
        summary.put("limitations", List.of(
                "Balance verification is a later Essentials balance observation, not a transaction-success receipt.",
                "Only isolated synchronous Jobs BufferedPaymentTask balance updates are counted; coverage may be incomplete.",
                "Pre-payment and batch amounts must not be added to verified balance deltas.",
                "No exact action/batch correlation or limit-reduction reason is exposed; unknown fields remain null.",
                "Per-player hours include all recorded playtime, not time spent working a job.",
                "Quest command rewards, taxes paid to server accounts, and unrelated transfers are excluded."));
        File totals = new File(directory, "jobs-summary.json");
        Files.writeString(totals.toPath(), Json.writePretty(summary), StandardCharsets.UTF_8);
        return List.of(detail, totals);
    }
}
