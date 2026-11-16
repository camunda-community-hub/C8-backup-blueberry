package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Secondary storage: Microsoft SQL Server (Camunda RDBMS secondary storage).
 * <p>
 * Camunda gives no API for the backup of the database: "You are responsible for backing up the RDBMS using your
 * database vendor's native tools". SQL Server has them in T-SQL, so the whole cycle is done through JDBC:
 * <ul>
 *   <li>start a backup: BACKUP DATABASE ... TO DISK/URL (a native full backup, named camunda_{backupId})</li>
 *   <li>list the backups: the history of msdb (msdb.dbo.backupset)</li>
 *   <li>restore: RESTORE DATABASE ... (method {@link #restore(long)}, not used by the backup)</li>
 * </ul>
 * The file is written by the SQL Server itself: backupPath is a path on the server (for example /var/opt/mssql/backup)
 * or an URL (https://account.blob.core.windows.net/container, with a credential created on the server).
 * <p>
 * Configuration:
 * <pre>
 * blueberry:
 *   secondaryStorage: sqlserver
 *   sqlserver:
 *     url: jdbc:sqlserver://host:1433;encrypt=true;trustServerCertificate=true
 *     username: sa
 *     password: ...
 *     database: camunda
 *     backupPath: /var/opt/mssql/backup
 * </pre>
 * The JDBC driver (com.microsoft.sqlserver:mssql-jdbc) must be in the class path.
 */
@Component
public class SqlServerConnect implements SecondaryStorageInt {
    public static final String NAME = "SqlServer";
    /**
     * The name of the backup in SQL Server: camunda_{backupId}
     */
    static final String BACKUP_NAME_PREFIX = "camunda_";
    private static final Pattern SAFE_DATABASE_NAME = Pattern.compile("[A-Za-z0-9_\\-]+");

    private final Logger logger = LoggerFactory.getLogger(SqlServerConnect.class);
    private final SecondaryStorageDetector secondaryStorageDetector;
    private final Map<Long, CompletableFuture<Void>> runningBackups = new ConcurrentHashMap<>();

    @Value("${blueberry.sqlserver.url:}")
    private String url;
    @Value("${blueberry.sqlserver.username:}")
    private String username;
    @Value("${blueberry.sqlserver.password:}")
    private String password;
    @Value("${blueberry.sqlserver.database:}")
    private String database;
    @Value("${blueberry.sqlserver.backupPath:}")
    private String backupPath;

    public SqlServerConnect(SecondaryStorageDetector secondaryStorageDetector) {
        this.secondaryStorageDetector = secondaryStorageDetector;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Active when SQL Server is the secondary storage of the cluster
     */
    @Override
    public boolean isActive() {
        return SecondaryStorageFactory.normalize(secondaryStorageDetector.detect().value())
                .equals(SecondaryStorageFactory.normalize(NAME));
    }

    @Override
    public CamundaApplicationInt.COMPONENT getComponent() {
        return CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE;
    }

    /* ******************************************************************** */
    /*  Backup                                                              */
    /* ******************************************************************** */

    /**
     * Start the backup: BACKUP DATABASE runs in the SQL Server while the connection is open, so it is executed in a
     * thread. waitBackup waits for its end.
     */
    @Override
    public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
        BackupOperation backupOperation = new BackupOperation();
        String problem = checkConfiguration();
        if (problem != null) {
            backupOperation.status = 400;
            backupOperation.title = "SQL Server is not configured";
            backupOperation.message = problem;
            return backupOperation;
        }
        String sql = backupStatement(backupId);
        operationLog.info(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "Start the SQL Server backup: " + sql);
        runningBackups.put(backupId, CompletableFuture.runAsync(() -> {
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(0); // a backup is long
                statement.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }));
        backupOperation.status = 202;
        return backupOperation;
    }

    @Override
    public BackupOperation waitBackup(Long backupId, OperationLog operationLog) throws BackupException {
        BackupOperation backupOperation = new BackupOperation();
        CompletableFuture<Void> future = runningBackups.remove(backupId);
        if (future == null) {
            backupOperation.status = 500;
            backupOperation.title = "No SQL Server backup in progress for " + backupId;
            return backupOperation;
        }
        try {
            future.get();
            operationLog.info(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "SQL Server backup " + backupId + " completed");
            backupOperation.status = 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            backupOperation.status = 500;
            backupOperation.title = "SQL Server backup interrupted";
        } catch (ExecutionException e) {
            backupOperation.status = 500;
            backupOperation.title = "SQL Server backup " + backupId + " failed";
            backupOperation.message = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            operationLog.error(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, backupOperation.title + ": " + backupOperation.message);
        }
        return backupOperation;
    }

    /* ******************************************************************** */
    /*  List                                                                */
    /* ******************************************************************** */

    /**
     * The backups made by Blueberry: the history of msdb, the backups named camunda_{backupId}
     */
    @Override
    public List<BackupInfo> getListBackups() throws OperationException {
        List<BackupInfo> listBackups = new ArrayList<>();
        if (!isActive() || checkConfiguration() != null) {
            return listBackups;
        }
        String sql = "SELECT name, backup_start_date, backup_finish_date FROM msdb.dbo.backupset "
                + "WHERE database_name = ? AND type = 'D' AND name LIKE 'camunda[_]%' ORDER BY backup_set_id";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("name");
                    Long backupId = parseBackupId(name);
                    if (backupId == null) {
                        continue;
                    }
                    BackupInfo backupInfo = new BackupInfo();
                    backupInfo.backupId = backupId;
                    backupInfo.components.add(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE);
                    // a row in the history is written when the backup is finished
                    backupInfo.status = BackupInfo.Status.COMPLETED;
                    Timestamp start = resultSet.getTimestamp("backup_start_date");
                    if (start != null) {
                        backupInfo.backupTime = start.toLocalDateTime();
                    }
                    listBackups.add(backupInfo);
                }
            }
        } catch (SQLException e) {
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "No Connection to SQL Server",
                    "Can't read the backup history of [" + database + "]: " + e.getMessage());
        }
        return listBackups;
    }

    @Override
    public String getUrlListBackup() {
        return url;
    }

    /* ******************************************************************** */
    /*  Restore                                                             */
    /* ******************************************************************** */

    /**
     * Restore the database from the backup. DESTRUCTIVE: the database is replaced (WITH REPLACE), all connections are
     * closed (SINGLE_USER). It is not called by the backup, and the Camunda cluster must be stopped before.
     *
     * @param backupId backup to restore
     * @throws OperationException if the restore fails
     */
    public void restore(long backupId) throws OperationException {
        String problem = checkConfiguration();
        if (problem != null) {
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "SQL Server is not configured", problem);
        }
        String source = backupSource(backupId);
        String db = "[" + database + "]";
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(0);
            statement.execute("ALTER DATABASE " + db + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
            try {
                statement.execute("RESTORE DATABASE " + db + " FROM " + source + " WITH REPLACE, RECOVERY");
            } finally {
                statement.execute("ALTER DATABASE " + db + " SET MULTI_USER");
            }
        } catch (SQLException e) {
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "SQL Server restore failed",
                    "Restore of [" + database + "] from backup " + backupId + ": " + e.getMessage());
        }
    }

    /* ******************************************************************** */
    /*  T-SQL                                                               */
    /* ******************************************************************** */

    /**
     * BACKUP DATABASE [db] TO DISK = N'path/camunda_12.bak' WITH INIT, CHECKSUM, NAME = N'camunda_12'
     */
    String backupStatement(long backupId) {
        return "BACKUP DATABASE [" + database + "] TO " + backupSource(backupId)
                + " WITH INIT, CHECKSUM, NAME = N'" + BACKUP_NAME_PREFIX + backupId + "'";
    }

    /**
     * DISK = N'/path/camunda_12.bak' or URL = N'https://.../camunda_12.bak'
     */
    String backupSource(long backupId) {
        String base = backupPath.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String file = base + "/" + BACKUP_NAME_PREFIX + backupId + ".bak";
        String kind = base.toLowerCase(Locale.ROOT).startsWith("http") ? "URL" : "DISK";
        return kind + " = N'" + file.replace("'", "''") + "'";
    }

    static Long parseBackupId(String backupName) {
        if (backupName == null || !backupName.startsWith(BACKUP_NAME_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(backupName.substring(BACKUP_NAME_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return null if the configuration is complete, else what is missing
     */
    String checkConfiguration() {
        if (url == null || url.isBlank()) {
            return "blueberry.sqlserver.url is not set";
        }
        if (database == null || !SAFE_DATABASE_NAME.matcher(database).matches()) {
            return "blueberry.sqlserver.database is not set, or is not a simple name (letters, digits, _ and -): [" + database + "]";
        }
        if (backupPath == null || backupPath.isBlank()) {
            return "blueberry.sqlserver.backupPath is not set (a path on the SQL Server, or an URL)";
        }
        return null;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
