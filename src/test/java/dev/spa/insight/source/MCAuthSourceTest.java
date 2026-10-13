package dev.spa.insight.source;

import dev.spa.insight.milestone.Milestones;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MCAuthSourceTest {
    private static final UUID LINKED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UNLINKED = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NOT_TARGETED = UUID.fromString("33333333-3333-3333-3333-333333333333");
    @TempDir Path folder;
    Path database;
    Path legacy;
    MCAuthSource source;

    @BeforeEach void setUp() throws Exception {
        Path auth = Files.createDirectory(folder.resolve("MCAuth"));
        database = auth.resolve("mcauth.db");
        legacy = auth.resolve("data.yml");
        source = new MCAuthSource(folder.toFile());
    }

    @Test void readsCurrentSchemaAndOnlyRequestedPlayersWithoutChangingDatabase() throws Exception {
        try (Connection connection = createDatabase()) {
            insert(connection, LINKED, "10001", "synthetic-user");
            insert(connection, NOT_TARGETED, "10003", "not-collected");
        }
        byte[] before = Files.readAllBytes(database);
        assertEquals(SourceStatus.AVAILABLE, source.probe().status());
        RecordingSink sink = collect();
        assertLinked(sink, "10001", "synthetic-user");
        assertUnlinked(sink, UNLINKED);
        assertFalse(sink.values.containsKey(NOT_TARGETED));
        assertEquals(Map.of(LINKED, sink.now()), sink.milestones);
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test void collectsCurrentSchemaFromCommittedWalAndObservesUnlink() throws Exception {
        try (Connection connection = createDatabase(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            insert(connection, LINKED, "10001", "synthetic-user");
            assertTrue(Files.size(Path.of(database + "-wal")) > 0);
            assertEquals(SourceStatus.AVAILABLE, source.probe().status());
            assertLinked(collect(), "10001", "synthetic-user");
            statement.execute("DELETE FROM authenticated_players WHERE uuid = '" + LINKED + "'");
            RecordingSink afterUnlink = collect();
            assertUnlinked(afterUnlink, LINKED);
            assertTrue(afterUnlink.milestones.isEmpty());
        }
    }

    @Test void databaseTakesPrecedenceOverStaleLegacyLinks() throws Exception {
        writeLegacy();
        try (Connection ignored = createDatabase()) { }
        assertEquals(SourceStatus.AVAILABLE, source.probe().status());
        assertTrue(source.probe().detail().endsWith("mcauth.db"));
        assertUnlinked(collect(), LINKED);
    }

    @Test void fallsBackToLegacyOnlyWhenDatabaseIsAbsent() throws Exception {
        writeLegacy();
        byte[] before = Files.readAllBytes(legacy);
        assertEquals(SourceStatus.AVAILABLE, source.probe().status());
        assertTrue(source.probe().detail().contains("legacy YAML"));
        RecordingSink sink = collect();
        assertLinked(sink, "10001", "synthetic-user");
        assertUnlinked(sink, UNLINKED);
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertFalse(Files.exists(database));
    }

    @Test void switchesToDatabaseIfItAppearsAfterLegacyProbe() throws Exception {
        writeLegacy();
        assertEquals(SourceStatus.AVAILABLE, source.probe().status());
        try (Connection ignored = createDatabase()) { }
        assertUnlinked(collect(), LINKED);
    }

    @Test void doesNotReturnToLegacyWhenPreviouslyObservedDatabaseDisappears() throws Exception {
        writeLegacy();
        try (Connection ignored = createDatabase()) { }
        assertEquals(SourceStatus.AVAILABLE, source.probe().status());
        Files.delete(database);
        assertEquals(SourceStatus.ERROR, source.probe().status());
        assertThrows(Exception.class, this::collect);
        assertFalse(Files.exists(database));
    }

    @Test void malformedDatabaseDoesNotFallBackToStaleLegacy() throws Exception {
        writeLegacy();
        Files.writeString(database, "not a database");
        byte[] before = Files.readAllBytes(database);
        assertEquals(SourceStatus.ERROR, source.probe().status());
        assertThrows(Exception.class, this::collect);
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test void databasePathThatIsDirectoryDoesNotFallBackToLegacy() throws Exception {
        writeLegacy();
        Files.createDirectory(database);
        assertEquals(SourceStatus.ERROR, source.probe().status());
        assertThrows(Exception.class, this::collect);
    }

    @Test void missingTableAndColumnsAreErrorsRatherThanUnlinkedPlayers() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertEquals(SourceStatus.ERROR, source.probe().status());
            assertThrows(Exception.class, this::collect);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE authenticated_players (uuid TEXT PRIMARY KEY)");
            }
            assertEquals(SourceStatus.ERROR, source.probe().status());
            assertThrows(Exception.class, this::collect);
        }
    }

    @Test void missingSourcesAreUnavailableAndDoNotCreateFiles() {
        assertEquals(SourceStatus.UNAVAILABLE, source.probe().status());
        assertThrows(Exception.class, this::collect);
        assertFalse(Files.exists(database));
        assertFalse(Files.exists(legacy));
    }

    @Test void malformedLegacyYamlAndWrongSectionShapesAreErrors() throws Exception {
        for (String content : List.of("authenticated: [\n", "authenticated: wrong\n",
                "authenticated:\n  " + LINKED + ": wrong\n")) {
            Files.writeString(legacy, content);
            assertEquals(SourceStatus.ERROR, source.probe().status(), content);
            assertThrows(Exception.class, this::collect, content);
        }
    }

    @Test void emptyLegacyContainsNoLinkedPlayers() throws Exception {
        Files.writeString(legacy, "authenticated: {}\n");
        assertEquals(SourceStatus.AVAILABLE, source.probe().status());
        RecordingSink sink = collect();
        assertUnlinked(sink, LINKED);
        assertUnlinked(sink, UNLINKED);
        assertTrue(sink.milestones.isEmpty());
    }

    private Connection createDatabase() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            // MCAuth f642f830 の VerificationStore と同一の保存形式。すべて架空データ。
            statement.execute("CREATE TABLE authenticated_players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, "
                    + "discord_user_id TEXT NOT NULL UNIQUE, discord_user_name TEXT NOT NULL)");
        }
        return connection;
    }

    private static void insert(Connection connection, UUID uuid, String discordId, String discordName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO authenticated_players (uuid, name, discord_user_id, discord_user_name) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, "SyntheticPlayer");
            statement.setString(3, discordId);
            statement.setString(4, discordName);
            statement.executeUpdate();
        }
    }

    private void writeLegacy() throws Exception {
        Files.writeString(legacy, "authenticated:\n  " + LINKED + ":\n"
                + "    name: SyntheticPlayer\n    discord-user-id: '10001'\n"
                + "    discord-user-name: synthetic-user\n");
    }

    private RecordingSink collect() throws Exception {
        RecordingSink sink = new RecordingSink();
        source.collect(sink);
        return sink;
    }

    private static void assertLinked(RecordingSink sink, String discordId, String discordName) {
        assertEquals(Map.of("linked", true, "discord_user_id", discordId, "discord_user_name", discordName),
                sink.values.get(LINKED));
        assertEquals(sink.now(), sink.milestones.get(LINKED));
    }

    private static void assertUnlinked(RecordingSink sink, UUID uuid) {
        assertEquals(Map.of("linked", false, "discord_user_id", "", "discord_user_name", ""), sink.values.get(uuid));
        assertFalse(sink.milestones.containsKey(uuid));
    }

    private static final class RecordingSink implements SourceAdapter.Sink {
        final Map<UUID, Map<String, Object>> values = new HashMap<>();
        final Map<UUID, Long> milestones = new HashMap<>();
        @Override public Collection<UUID> targets() { return List.of(LINKED, UNLINKED); }
        @Override public long now() { return 1_800_000_000_000L; }
        @Override public void value(UUID uuid, String key, Object value) {
            values.computeIfAbsent(uuid, ignored -> new HashMap<>()).put(key, value);
        }
        @Override public void milestone(UUID uuid, String key, long achievedAt, String detail) {
            assertEquals(Milestones.FIRST_DISCORD_LINK, key);
            assertEquals("mcauth", detail);
            milestones.put(uuid, achievedAt);
        }
    }
}
