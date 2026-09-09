package dev.spa.insight.jobs;

import dev.spa.insight.storage.Database;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobsAuditTest {
    @TempDir Path folder;
    @Test void verificationNeverCountsAsyncOverlapMismatchOrUnknown() {
        BigDecimal a = new BigDecimal("12.50");
        assertEquals("balance_verified", BalanceEvidence.status(false, false, a, new BigDecimal("12.5")));
        assertEquals("unverified_async", BalanceEvidence.status(true, false, a, a));
        assertEquals("ambiguous", BalanceEvidence.status(false, true, a, a));
        assertEquals("balance_mismatch", BalanceEvidence.status(false, false, a, BigDecimal.TEN));
        assertEquals("unverified", BalanceEvidence.status(false, false, a, null));
    }
    private StackTraceElement frame(String name, String method) { return new StackTraceElement(name, method, "x", 1); }
    @Test void excludesPayCommandsAndNestedListenerTransfers() {
        var task = frame("com.gamingmesh.jobs.tasks.BufferedPaymentTask", "run");
        var vault = frame("com.gamingmesh.jobs.economy.VaultEconomy", "depositPlayer");
        var set = frame("com.earth2me.essentials.User", "setMoney");
        assertTrue(BalanceEvidence.jobsPaymentOrigin(new StackTraceElement[]{task,vault,set}));
        assertFalse(BalanceEvidence.jobsPaymentOrigin(new StackTraceElement[]{vault,set}));
        assertFalse(BalanceEvidence.jobsPaymentOrigin(new StackTraceElement[]{task,vault,set,set}));
    }
    @Test void migrationIdempotenceAndExportExcludePlansAndCancelledBatches() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Database db = new Database(plugin);
        db.connect();
        var store = new JobsAuditStore(db);
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", "same-observation"); row.put("uuid", "player"); row.put("occurred_at", 1000L);
        row.put("phase", "pre_payment"); row.put("status", "pending"); row.put("job", "Miner");
        row.put("planned_money", "100.123456789012345");
        store.save(row);
        row.put("status", "cancelled"); store.save(row);
        assertEquals(1, db.queryLong("SELECT COUNT(*) FROM jobs_audit"));
        row = new LinkedHashMap<>(row); row.put("id", "batch"); row.put("phase", "payment");
        row.put("event_money", "100"); store.save(row);
        row = new LinkedHashMap<>(row); row.put("id", "receipt"); row.put("phase", "balance");
        row.put("status", "balance_verified"); row.put("verified_delta", "0.123456789012345"); store.save(row);
        row = new LinkedHashMap<>(row); row.put("id", "incomplete"); row.put("status", "pending");
        row.remove("verified_delta"); store.save(row);
        db.close(); db.connect();
        assertEquals(1, db.queryLong("SELECT COUNT(*) FROM jobs_audit WHERE status='unverified_restart'"));
        var files = JobsAuditExport.write(db, folder.toFile(), 0, 2000);
        String summary = Files.readString(files.get(1).toPath());
        assertTrue(summary.contains("\"verified_net_balance_delta\": 0.123456789012345"));
        assertTrue(summary.contains("\"Miner\": 100.123456789012345"));
        assertTrue(summary.contains("\"income_by_job\": null"));
        assertEquals(4, Files.readAllLines(files.get(0).toPath()).size());
        assertEquals(4, db.queryLong("SELECT COUNT(*) FROM jobs_audit WHERE limit_reduced IS NULL"));
        db.close();
    }
}
