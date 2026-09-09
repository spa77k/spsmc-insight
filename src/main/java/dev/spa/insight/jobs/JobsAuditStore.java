package dev.spa.insight.jobs;

import dev.spa.insight.storage.Database;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Append observations, then finalize the same row. Decimal currency values stay as TEXT. */
public final class JobsAuditStore {
    public static final String COLUMNS = "id,occurred_at,uuid,session_id,phase,status,job,job_level,action,target,world,"
            + "planned_money,event_money,exp,points,balance_before,balance_after,observed_balance,verified_delta,"
            + "limit_reduced,detail";
    private final Database database;
    public JobsAuditStore(Database database) { this.database = database; }

    public static void migrate(Statement s) throws SQLException {
        s.execute("""
            CREATE TABLE IF NOT EXISTS jobs_audit (
              id TEXT PRIMARY KEY, occurred_at INTEGER NOT NULL, uuid TEXT NOT NULL,
              session_id INTEGER, phase TEXT NOT NULL, status TEXT NOT NULL,
              job TEXT, job_level INTEGER, action TEXT, target TEXT, world TEXT,
              planned_money TEXT, event_money TEXT, exp TEXT, points TEXT,
              balance_before TEXT, balance_after TEXT, observed_balance TEXT, verified_delta TEXT,
              limit_reduced INTEGER, detail TEXT
            )
            """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_jobs_audit_time ON jobs_audit(occurred_at)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_jobs_audit_player ON jobs_audit(uuid,occurred_at)");
        // An interrupted verification must never become revenue on restart.
        s.execute("UPDATE jobs_audit SET status='unverified_restart' WHERE status='pending'");
    }

    public void save(Map<String, Object> row) throws SQLException {
        String[] columns = COLUMNS.split(",");
        Object[] values = new Object[columns.length];
        StringBuilder updates = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            values[i] = row.get(columns[i]);
            if (i > 0) {
                if (i > 1) updates.append(',');
                updates.append(columns[i]).append("=excluded.").append(columns[i]);
            }
        }
        try (var s = database.prepare("INSERT INTO jobs_audit (" + COLUMNS + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(columns.length, "?"))
                + ") ON CONFLICT(id) DO UPDATE SET " + updates, values)) {
            s.executeUpdate();
        }
    }

    public static Map<String, Object> row(java.sql.ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : COLUMNS.split(",")) row.put(column, rs.getObject(column));
        return row;
    }
}
