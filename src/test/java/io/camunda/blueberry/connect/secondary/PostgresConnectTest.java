package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.BackupInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresConnectTest {

    /**
     * Postgres is the secondary storage given by the configuration (the actuator does not answer)
     */
    private static PostgresConnect connect(String configuredStorage, String directory) {
        PostgresConnect connect = new PostgresConnect(TestDetector.detector(configuredStorage, null));
        TestFields.set(connect, "host", "db.example.com");
        TestFields.set(connect, "port", 5433);
        TestFields.set(connect, "database", "camunda");
        TestFields.set(connect, "username", "camunda_user");
        TestFields.set(connect, "password", "s3cret");
        TestFields.set(connect, "backupDirectory", directory);
        TestFields.set(connect, "pgDump", "pg_dump");
        TestFields.set(connect, "pgRestore", "pg_restore");
        return connect;
    }

    @Test
    void theBackupCommandUsesTheCustomFormatAndNeverTheSecret() {
        List<String> command = connect("postgres", "/backups").backupCommand(Path.of("/backups/camunda_7.dump.part"));

        assertEquals("pg_dump", command.get(0));
        assertTrue(command.contains("--host=db.example.com"));
        assertTrue(command.contains("--port=5433"));
        assertTrue(command.contains("--username=camunda_user"));
        assertTrue(command.contains("--dbname=camunda"));
        assertTrue(command.contains("--format=custom"));
        assertTrue(command.contains("--no-password"));
        assertTrue(command.stream().anyMatch(a -> a.startsWith("--file=") && a.endsWith("camunda_7.dump.part")));
        assertFalse(String.join(" ", command).contains("s3cret"), "the password must not be in the command line");
    }

    @Test
    void theRestoreCommandReplacesTheObjectsInOneTransaction() {
        List<String> command = connect("postgres", "/backups").restoreCommand(Path.of("/backups/camunda_7.dump"));

        assertEquals("pg_restore", command.get(0));
        assertTrue(command.contains("--clean"));
        assertTrue(command.contains("--if-exists"));
        assertTrue(command.contains("--single-transaction"));
        assertTrue(command.get(command.size() - 1).endsWith("camunda_7.dump"));
        assertFalse(String.join(" ", command).contains("s3cret"));
    }

    @Test
    void theNameOfTheFileGivesTheBackupId() {
        assertEquals(42L, PostgresConnect.parseBackupId("camunda_42.dump"));
        assertNull(PostgresConnect.parseBackupId("camunda_42.dump.part"));
        assertNull(PostgresConnect.parseBackupId("camunda_x.dump"));
        assertNull(PostgresConnect.parseBackupId("other_42.dump"));
        assertNull(PostgresConnect.parseBackupId(null));
    }

    @Test
    void theNamesGivenToTheProgramsAreSimple() {
        assertNull(connect("postgres", "/b").checkConfiguration());

        PostgresConnect option = connect("postgres", "/b");
        TestFields.set(option, "database", "--help");
        assertNotNull(option.checkConfiguration());

        PostgresConnect space = connect("postgres", "/b");
        TestFields.set(space, "username", "a b");
        assertNotNull(space.checkConfiguration());

        assertNotNull(connect("postgres", "").checkConfiguration());
    }

    @Test
    void theBackupsAreTheCompleteFilesOfTheDirectory(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("camunda_3.dump"), "x");
        Files.writeString(directory.resolve("camunda_10.dump"), "x");
        // a backup in progress, and other files: not backups
        Files.writeString(directory.resolve("camunda_11.dump.part"), "x");
        Files.writeString(directory.resolve("notes.txt"), "x");

        List<BackupInfo> backups = connect("postgres", directory.toString()).getListBackups();

        assertEquals(List.of(3L, 10L), backups.stream().map(b -> b.backupId).sorted().toList());
        assertTrue(backups.stream().allMatch(b -> b.status == BackupInfo.Status.COMPLETED && b.backupTime != null));
    }

    @Test
    void theListIsEmptyWhenPostgresIsNotTheSecondaryStorage(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("camunda_3.dump"), "x");
        assertTrue(connect("elasticsearch", directory.toString()).getListBackups().isEmpty());
    }

    @Test
    void postgresqlIsAnotherNameOfPostgres() {
        PostgresConnect connect = connect("postgresql", "/b");
        assertTrue(connect.isActive());
        assertTrue(connect.isDatabase());
        assertTrue(connect.supportsRestore());
        assertTrue(connect.getAliases().contains("postgresql"));
    }
}
