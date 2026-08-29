package dev.spa.insight.export;

import dev.spa.insight.storage.Database;
import dev.spa.insight.util.Json;
import dev.spa.insight.util.TimeUtil;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 初参加週ごとのコホートと D1/D7/D14/D30 の残存率。
 * 「ちょうどその日に来たか」と「その日までに一度でも来たか」の両方を出す。
 */
public class CohortCalculator {

    private static final int[] HORIZONS = {1, 7, 14, 30};

    private final Database database;

    public CohortCalculator(Database database) {
        this.database = database;
    }

    private static final class Member {
        private final String uuid;
        private String name;
        private long firstSeen;
        private String firstDay;
        private final TreeSet<String> activeDays = new TreeSet<>();

        private Member(String uuid) {
            this.uuid = uuid;
        }
    }

    public Map<String, Object> build(long now) throws SQLException {
        Map<String, Member> members = new LinkedHashMap<>();
        try (PreparedStatement statement = database.prepare(
                "SELECT uuid, name, first_seen FROM players ORDER BY first_seen");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Member member = new Member(result.getString(1));
                member.name = result.getString(2);
                member.firstSeen = result.getLong(3);
                member.firstDay = TimeUtil.dayKey(member.firstSeen);
                members.put(member.uuid, member);
            }
        }
        try (PreparedStatement statement = database.prepare(
                "SELECT uuid, day FROM daily_activity WHERE playtime_ms > 0 OR sessions > 0");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                Member member = members.get(result.getString(1));
                if (member != null) {
                    member.activeDays.add(result.getString(2));
                }
            }
        }

        Map<String, List<Member>> byCohort = new TreeMap<>();
        for (Member member : members.values()) {
            if (member.firstSeen <= 0) {
                continue;
            }
            byCohort.computeIfAbsent(TimeUtil.weekKey(member.firstSeen), key -> new ArrayList<>()).add(member);
        }

        List<Object> cohorts = new ArrayList<>();
        for (Map.Entry<String, List<Member>> entry : byCohort.entrySet()) {
            cohorts.add(describeCohort(entry.getKey(), entry.getValue(), now));
        }

        Map<String, Object> root = Json.obj();
        root.put("generated_at", TimeUtil.stamp(now));
        root.put("timezone", TimeUtil.ZONE.getId());
        root.put("definition", definition());
        root.put("cohort_count", cohorts.size());
        root.put("player_count", members.size());
        root.put("cohorts", cohorts);
        return root;
    }

    private Map<String, Object> definition() {
        Map<String, Object> definition = Json.obj();
        definition.put("cohort", "初参加日が属するISO週（JSTの月曜0時始まり）");
        definition.put("active_day", "daily_activity に記録がある日。ログインまたは滞在時間があれば活動日とみなす");
        definition.put("exact", "初参加日からちょうどN日後に活動があった人の割合");
        definition.put("within", "初参加日の翌日からN日後までの間に一度でも活動があった人の割合");
        definition.put("mature", "そのコホート全員がN日を経過しているか。false の割合は途中経過");
        definition.put("horizons", List.of(1, 7, 14, 30));
        return definition;
    }

    private Map<String, Object> describeCohort(String weekKey, List<Member> cohort, long now) {
        Map<String, Object> node = Json.obj();
        node.put("cohort_week", weekKey);
        node.put("cohort_start", TimeUtil.dayKey(TimeUtil.weekStartDate(weekKey)));
        node.put("players", cohort.size());

        Map<String, Object> retention = Json.obj();
        long cohortEnd = TimeUtil.weekEndMillis(weekKey);
        for (int horizon : HORIZONS) {
            int exact = 0;
            int within = 0;
            for (Member member : cohort) {
                if (member.activeDays.contains(shift(member.firstDay, horizon))) {
                    exact++;
                }
                if (activeWithin(member, horizon)) {
                    within++;
                }
            }
            Map<String, Object> bucket = Json.obj();
            bucket.put("exact", exact);
            bucket.put("exact_rate", rate(exact, cohort.size()));
            bucket.put("within", within);
            bucket.put("within_rate", rate(within, cohort.size()));
            bucket.put("mature", now >= cohortEnd + horizon * 86_400_000L);
            retention.put("d" + horizon, bucket);
        }
        node.put("retention", retention);

        List<Object> memberNodes = new ArrayList<>();
        for (Member member : cohort) {
            Map<String, Object> row = Json.obj();
            row.put("uuid", member.uuid);
            row.put("name", member.name);
            row.put("first_seen", TimeUtil.stamp(member.firstSeen));
            row.put("first_day", member.firstDay);
            row.put("active_day_count", member.activeDays.size());
            row.put("last_active_day", member.activeDays.isEmpty() ? null : member.activeDays.last());
            row.put("lifespan_days", member.activeDays.isEmpty()
                    ? 0
                    : TimeUtil.daysBetween(member.firstDay, member.activeDays.last()));
            row.put("active_days", new ArrayList<>(member.activeDays));
            memberNodes.add(row);
        }
        node.put("members", memberNodes);
        return node;
    }

    private boolean activeWithin(Member member, int horizon) {
        for (int day = 1; day <= horizon; day++) {
            if (member.activeDays.contains(shift(member.firstDay, day))) {
                return true;
            }
        }
        return false;
    }

    private static String shift(String dayKey, int days) {
        return TimeUtil.dayKey(TimeUtil.parseDay(dayKey).plusDays(days));
    }

    private static double rate(int part, int total) {
        return total == 0 ? 0.0 : Math.round((double) part / total * 10000.0) / 10000.0;
    }
}
