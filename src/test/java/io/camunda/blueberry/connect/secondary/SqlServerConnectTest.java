package io.camunda.blueberry.connect.secondary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqlServerConnectTest {

    private SqlServerConnect connect(String path, String database) {
        SqlServerConnect connect = new SqlServerConnect(null);
        TestFields.set(connect, "url", "jdbc:sqlserver://host:1433");
        TestFields.set(connect, "database", database);
        TestFields.set(connect, "backupPath", path);
        return connect;
    }

    @Test
    void backupToADiskPath() {
        assertEquals("BACKUP DATABASE [camunda] TO DISK = N'/var/opt/mssql/backup/camunda_12.bak' WITH INIT, CHECKSUM, NAME = N'camunda_12'",
                connect("/var/opt/mssql/backup/", "camunda").backupStatement(12));
    }

    @Test
    void backupToAnUrl() {
        assertEquals("URL = N'https://account.blob.core.windows.net/backups/camunda_7.bak'",
                connect("https://account.blob.core.windows.net/backups", "camunda").backupSource(7));
    }

    @Test
    void aQuoteInThePathIsEscaped() {
        assertEquals("DISK = N'/it''s/camunda_1.bak'", connect("/it's", "camunda").backupSource(1));
    }

    @Test
    void theNameOfTheBackupGivesItsId() {
        assertEquals(42L, SqlServerConnect.parseBackupId("camunda_42"));
        assertNull(SqlServerConnect.parseBackupId("camunda_x"));
        assertNull(SqlServerConnect.parseBackupId("other_42"));
        assertNull(SqlServerConnect.parseBackupId(null));
    }

    @Test
    void theDatabaseNameMustBeSimple() {
        assertNull(connect("/b", "camunda").checkConfiguration());
        assertNotNull(connect("/b", "camunda]; DROP DATABASE x; --").checkConfiguration());
        assertNotNull(connect("/b", "").checkConfiguration());
        assertNotNull(connect("", "camunda").checkConfiguration());
    }
}
