package dev.spa.insight.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SqliteReaderTest {
    @TempDir Path folder;

    @Test void readsCommittedWalRowsAndLaterCommitsWithoutCheckpoint() throws Exception {
        Path file = folder.resolve("live.db");
        try (Connection writer = openWriter(file); Statement writes = writer.createStatement()) {
            writes.execute("PRAGMA journal_mode=WAL");
            writes.execute("PRAGMA wal_autocheckpoint=0");
            writes.execute("CREATE TABLE records (id INTEGER PRIMARY KEY)");
            writes.execute("INSERT INTO records VALUES (1)");
            writes.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            byte[] checkpointed = Files.readAllBytes(file);
            writes.execute("INSERT INTO records VALUES (2)");
            assertTrue(Files.size(Path.of(file + "-wal")) > 0);
            byte[] wal = Files.readAllBytes(Path.of(file + "-wal"));

            try (Connection reader = SqliteReader.openReadOnly(file.toFile())) {
                assertEquals(2, count(reader));
                assertTrue(reader.isReadOnly());
                assertArrayEquals(checkpointed, Files.readAllBytes(file));
                assertArrayEquals(wal, Files.readAllBytes(Path.of(file + "-wal")));

                // 書き込み中の未コミット行を読まず、COMMIT後の次の読み取りで観測する。
                writes.execute("BEGIN IMMEDIATE");
                writes.execute("INSERT INTO records VALUES (3)");
                assertEquals(2, count(reader));
                writes.execute("COMMIT");
                assertEquals(3, count(reader));
            }
            assertArrayEquals(checkpointed, Files.readAllBytes(file));
        }
    }

    @Test void preventsDataAndSchemaWrites() throws Exception {
        Path file = folder.resolve("readonly.db");
        try (Connection writer = openWriter(file); Statement s = writer.createStatement()) {
            s.execute("CREATE TABLE records (id INTEGER PRIMARY KEY)");
            s.execute("INSERT INTO records VALUES (1)");
        }
        byte[] before = Files.readAllBytes(file);
        try (Connection reader = SqliteReader.openReadOnly(file.toFile()); Statement s = reader.createStatement()) {
            assertTrue(reader.isReadOnly());
            for (String sql : new String[]{"INSERT INTO records VALUES (2)",
                    "UPDATE records SET id = 3", "DELETE FROM records", "DROP TABLE records"}) {
                SQLException error = assertThrows(SQLException.class, () -> s.executeUpdate(sql), sql);
                assertTrue(error.getMessage().contains("SQLITE_READONLY"), error.getMessage());
            }
            assertEquals(1, count(reader));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void respectsExclusiveLocksAndUsesBoundedBusyTimeout() throws Exception {
        Path file = folder.resolve("locked.db");
        try (Connection writer = openWriter(file); Statement writes = writer.createStatement()) {
            writes.execute("CREATE TABLE records (id INTEGER PRIMARY KEY)");
            writes.execute("INSERT INTO records VALUES (1)");
            try (Connection reader = SqliteReader.openReadOnly(file.toFile()); Statement reads = reader.createStatement()) {
                writes.execute("BEGIN EXCLUSIVE");
                try {
                    SQLException error = assertThrows(SQLException.class, () -> count(reader));
                    assertTrue(error.getMessage().contains("SQLITE_BUSY"), error.getMessage());
                } finally {
                    writes.execute("ROLLBACK");
                }
                assertEquals(1, count(reader));
                try (ResultSet result = reads.executeQuery("PRAGMA busy_timeout")) {
                    assertTrue(result.next());
                    assertEquals(1000, result.getInt(1));
                }
            }
        }
    }

    @Test void escapesUriCharactersInDatabasePath() throws Exception {
        Path file = folder.resolve("日本語 space ?mode=rw&#% database.db");
        try (Connection writer = openWriter(file); Statement s = writer.createStatement()) {
            s.execute("CREATE TABLE records (id INTEGER PRIMARY KEY)");
            s.execute("INSERT INTO records VALUES (1)");
        }
        try (Connection reader = SqliteReader.openReadOnly(file.toFile()); Statement s = reader.createStatement()) {
            assertEquals(1, count(reader));
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM records"));
        }
    }

    @Test void missingAndDirectorySourcesAreNotCreatedOrOpened() {
        Path missing = folder.resolve("missing.db");
        assertThrows(SQLException.class, () -> SqliteReader.openReadOnly(null));
        assertThrows(SQLException.class, () -> SqliteReader.openReadOnly(missing.toFile()));
        assertThrows(SQLException.class, () -> SqliteReader.openReadOnly(folder.toFile()));
        assertFalse(Files.exists(missing));
    }

    @Test void malformedDatabaseFailsReadingWithoutChangingIt() throws Exception {
        Path file = folder.resolve("malformed.db");
        Files.writeString(file, "synthetic data, not a SQLite database");
        byte[] before = Files.readAllBytes(file);
        assertThrows(SQLException.class, () -> {
            try (Connection reader = SqliteReader.openReadOnly(file.toFile())) {
                SqliteReader.tables(reader);
            }
        });
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    private static Connection openWriter(Path file) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file.toUri().toASCIIString());
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet result = s.executeQuery("SELECT COUNT(*) FROM records")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
