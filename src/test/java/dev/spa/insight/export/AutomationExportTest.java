package dev.spa.insight.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutomationExportTest {
    @TempDir Path folder;

    @Test void missingDatabaseIsReportedWithoutFile() throws Exception {
        Map<String, Object> summary = AutomationExport.write(folder.toFile(), folder.toFile(), 0, 1);
        assertEquals(false, summary.get("available"));
        assertNull(summary.get("file"));
    }

    @Test void exportsWeekRowsAndRepeatNames() throws Exception {
        File plugins = folder.resolve("plugins").toFile();
        File eco = new File(plugins, "EcoLifeAssist");
        assertTrue(eco.mkdirs());
        // EcoLifeAssist の AutomationStore と同じ表
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + new File(eco, "automation.db"));
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE detections (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " world TEXT NOT NULL, chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL,"
                    + " x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,"
                    + " owner TEXT NOT NULL, placer TEXT NOT NULL, placed_at INTEGER NOT NULL, map_url TEXT NOT NULL,"
                    + " transfer INTEGER NOT NULL, pickup INTEGER NOT NULL, piston INTEGER NOT NULL,"
                    + " dispense INTEGER NOT NULL, mob_death INTEGER NOT NULL,"
                    + " detected_at INTEGER NOT NULL, content TEXT NOT NULL, sent INTEGER NOT NULL DEFAULT 0,"
                    + " UNIQUE(world, chunk_x, chunk_z))");
            s.execute("INSERT INTO detections VALUES (1,'build',1,1,20,64,20,'alice','alice',1700000000,'https://m/#build',"
                    + "30,0,0,0,0,1000,'x',1)");
            s.execute("INSERT INTO detections VALUES (2,'build',2,2,40,64,40,'alice','',0,'',0,9,0,0,12,2000,'x',0)");
            s.execute("INSERT INTO detections VALUES (3,'resource',9,9,150,64,150,'','bob',0,'',5,0,0,0,0,9000,'x',1)");
        }
        File out = folder.resolve("out").toFile();
        assertTrue(out.mkdirs());

        Map<String, Object> summary = AutomationExport.write(plugins, out, 0, 5000);

        assertEquals(true, summary.get("available"));
        assertEquals(2, summary.get("detections_this_week"));
        assertEquals(3L, summary.get("detections_total"));
        assertEquals(Map.of("alice", 2L), summary.get("repeat_claim_owners"));
        assertEquals(Map.of(), summary.get("repeat_placers"));
        List<String> lines = Files.readAllLines(new File(out, "automation.jsonl").toPath());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"placer\":\"alice\""), lines.get(0));
        assertTrue(lines.get(1).contains("\"placer\":null") && lines.get(1).contains("\"mob_death\":12"), lines.get(1));
    }
}
