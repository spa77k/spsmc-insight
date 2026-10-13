package dev.spa.insight.source;

import dev.spa.insight.InsightConfig;
import dev.spa.insight.milestone.MilestoneRecorder;
import dev.spa.insight.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceRegistryLockTest {
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @TempDir Path folder;
    Database insight;
    SourceRegistry registry;
    Path authDatabase;

    @BeforeEach void setUp() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.resolve("SPSMCInsight").toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        insight = new Database(plugin);
        insight.connect();
        registry = new SourceRegistry(insight, new InsightConfig(new YamlConfiguration()),
                new MilestoneRecorder(insight, Runnable::run, Logger.getAnonymousLogger()), Logger.getAnonymousLogger());
        authDatabase = Files.createDirectory(folder.resolve("MCAuth")).resolve("mcauth.db");
    }

    @AfterEach void close() {
        if (insight != null) insight.close();
    }

    @Test void retriesBusyInitialProbeOnNextCollectionAndRecordsSnapshot() throws Exception {
        try (Connection writer = createAuth(); Statement statement = writer.createStatement()) {
            statement.execute("BEGIN EXCLUSIVE");
            try {
                register(new MCAuthSource(folder.toFile()));
                assertRetryableError();
            } finally {
                statement.execute("ROLLBACK");
            }
            registry.collectAll(List.of(PLAYER));
            assertRecoveredSnapshot();
        }
    }

    @Test void retriesBusyCollectionWithoutDisablingSourcePermanently() throws Exception {
        try (Connection writer = createAuth(); Statement statement = writer.createStatement()) {
            register(new MCAuthSource(folder.toFile()));
            assertTrue(registry.statuses().get("mcauth").usable());
            statement.execute("BEGIN EXCLUSIVE");
            try {
                registry.collectAll(List.of(PLAYER));
                assertRetryableError();
                // 次の周回でもロック中なら欠測のまま保ち、その次で復帰できる。
                registry.collectAll(List.of(PLAYER));
                assertRetryableError();
                assertEquals(0, insight.queryLong("SELECT COUNT(*) FROM external_snapshots"));
            } finally {
                statement.execute("ROLLBACK");
            }
            registry.collectAll(List.of(PLAYER));
            assertRecoveredSnapshot();
        }
    }

    @Test void malformedSourceStillStaysDisabledAfterError() throws Exception {
        Files.writeString(authDatabase, "not SQLite");
        MCAuthSource source = spy(new MCAuthSource(folder.toFile()));
        register(source);
        assertEquals(SourceStatus.ERROR, registry.statuses().get("mcauth").status());
        assertFalse(registry.statuses().get("mcauth").retryable());
        registry.collectAll(List.of(PLAYER));
        registry.collectAll(List.of(PLAYER));
        verify(source, times(1)).probe();
        verify(source, never()).collect(any());
        assertEquals(0, insight.queryLong("SELECT COUNT(*) FROM external_snapshots"));
    }

    @Test void onlySqliteBusyAndLockedAreRetryableIncludingWrappedErrors() {
        for (SQLiteErrorCode code : List.of(SQLiteErrorCode.SQLITE_BUSY, SQLiteErrorCode.SQLITE_LOCKED,
                SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT, SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE)) {
            SourceStatus status = SourceStatus.error("synthetic", new IllegalStateException(
                    new SQLiteException("synthetic lock", code)));
            assertEquals(SourceStatus.ERROR, status.status());
            assertTrue(status.retryable());
            assertFalse(status.usable());
        }
        assertFalse(SourceStatus.error("synthetic", new SQLiteException(
                "synthetic corruption", SQLiteErrorCode.SQLITE_CORRUPT)).retryable());
        assertFalse(SourceStatus.error("synthetic", new IllegalStateException("SQLITE_BUSY text alone")).retryable());
        assertFalse(SourceStatus.disabled("synthetic").retryable());
        assertFalse(SourceStatus.unavailable("synthetic", "missing").retryable());
    }

    @Test void otherSqliteAdaptersPreserveTransientProbeErrors() throws Exception {
        Path contracts = Files.createDirectory(folder.resolve("ContractBoard")).resolve("irai.db");
        Path core = Files.createDirectory(folder.resolve("CoreProtect")).resolve("database.db");
        for (Path path : List.of(contracts, core)) {
            try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + path);
                 Statement statement = writer.createStatement()) {
                statement.execute("CREATE TABLE requests (id INTEGER)");
                statement.execute("CREATE TABLE co_user (id INTEGER)");
                SourceAdapter adapter = path.equals(contracts) ? new ContractBoardSource(folder.toFile())
                        : new CoreProtectSource(folder.toFile());
                statement.execute("BEGIN EXCLUSIVE");
                try {
                    SourceStatus status = adapter.probe();
                    assertEquals(SourceStatus.ERROR, status.status());
                    assertTrue(status.retryable());
                } finally {
                    statement.execute("ROLLBACK");
                }
                assertTrue(adapter.probe().usable());
            }
        }
    }

    private void assertRetryableError() {
        SourceStatus status = registry.statuses().get("mcauth");
        assertEquals(SourceStatus.ERROR, status.status());
        assertTrue(status.retryable());
        assertFalse(status.usable());
    }

    private void assertRecoveredSnapshot() throws Exception {
        assertTrue(registry.statuses().get("mcauth").usable());
        assertFalse(registry.statuses().get("mcauth").retryable());
        assertEquals(1, insight.queryLong("SELECT COUNT(*) FROM external_snapshots "
                + "WHERE source='mcauth' AND key='linked' AND value='true'"));
        assertEquals(1, insight.queryLong("SELECT COUNT(*) FROM source_status WHERE source='mcauth' AND status='available'"));
    }

    private Connection createAuth() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + authDatabase);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE authenticated_players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "discord_user_id TEXT NOT NULL UNIQUE, discord_user_name TEXT NOT NULL)");
            statement.execute("INSERT INTO authenticated_players VALUES ('" + PLAYER
                    + "', 'SyntheticPlayer', '10001', 'synthetic-user')");
        }
        return connection;
    }

    private void register(SourceAdapter adapter) throws Exception {
        Method register = SourceRegistry.class.getDeclaredMethod("register", Supplier.class);
        register.setAccessible(true);
        register.invoke(registry, (Supplier<SourceAdapter>) () -> adapter);
    }
}
