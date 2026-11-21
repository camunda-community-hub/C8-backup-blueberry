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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Secondary storage: PostgreSQL (Camunda RDBMS secondary storage).
 * <p>
 * Camunda gives no API for the backup of the database: "You are responsible for backing up the RDBMS using your database
 * vendor's native tools". PostgreSQL has no SQL command to take a backup, to list the backups or to restore one: the
 * native tools are programs. Blueberry runs them:
 * <ul>
 *   <li>backup: pg_dump, custom format, in the file {backupDirectory}/camunda_{backupId}.dump</li>
 *   <li>list: the files camunda_{backupId}.dump of the directory (the file is complete: it is written under another
 *   name, then renamed)</li>
 *   <li>restore: pg_restore --clean --if-exists --single-transaction</li>
 * </ul>
 * So pg_dump and pg_restore must be installed where Blueberry runs (package postgresql-client, or the Docker image of
 * Blueberry), with a version equal or newer to the server. The backup directory must be a directory of the machine where
 * Blueberry runs (a volume, a mounted share).
 * <p>
 * Configuration:
 * <pre>
 * blueberry:
 *   secondaryStorage: postgres
 *   postgres:
 *     host: localhost
 *     port: 5432
 *     database: camunda
 *     username: camunda
 *     password: ...
 *     backupDirectory: /backups/postgres
 *     # pgDump: /usr/bin/pg_dump      (default: pg_dump, in the PATH)
 *     # pgRestore: /usr/bin/pg_restore
 * </pre>
 * The password is given to the programs by the environment variable PGPASSWORD, never in the command line.
 */
@Component
public class PostgresConnect implements SecondaryStorageInt {
    public static final String NAME = "Postgres";
    static final String FILE_PREFIX = "camunda_";
    static final String FILE_SUFFIX = ".dump";
    private static final Pattern BACKUP_FILE = Pattern.compile("^camunda_(\\d+)\\.dump$");
    /**
     * Names given to the programs as arguments: no space, and never an option (-x)
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.@-]*");
    private static final int MAX_OUTPUT = 4000;

    private final Logger logger = LoggerFactory.getLogger(PostgresConnect.class);
    private final SecondaryStorageDetector secondaryStorageDetector;
    private final Map<Long, CompletableFuture<Void>> runningBackups = new ConcurrentHashMap<>();

    @Value("${blueberry.postgres.host:localhost}")
    private String host;
    @Value("${blueberry.postgres.port:5432}")
    private int port;
    @Value("${blueberry.postgres.database:}")
    private String database;
    @Value("${blueberry.postgres.username:}")
    private String username;
    @Value("${blueberry.postgres.password:}")
    private String password;
    @Value("${blueberry.postgres.backupDirectory:}")
    private String backupDirectory;
    @Value("${blueberry.postgres.pgDump:pg_dump}")
    private String pgDump;
    @Value("${blueberry.postgres.pgRestore:pg_restore}")
    private String pgRestore;

    public PostgresConnect(SecondaryStorageDetector secondaryStorageDetector) {
        this.secondaryStorageDetector = secondaryStorageDetector;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<String> getAliases() {
        return List.of("postgresql", "pg");
    }

    /**
     * Active when PostgreSQL is the secondary storage of the cluster
     */
    @Override
    public boolean isActive() {
        String value = SecondaryStorageFactory.normalize(secondaryStorageDetector.detect().value());
        return value.equals(SecondaryStorageFactory.normalize(NAME)) || value.equals("postgresql");
    }

    @Override
    public CamundaApplicationInt.COMPONENT getComponent() {
        return CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE;
    }

    /* ******************************************************************** */
    /*  Backup                                                              */
    /* ******************************************************************** */

    /**
     * Start pg_dump in a thread. waitBackup waits for its end.
     */
    @Override
    public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
        BackupOperation backupOperation = new BackupOperation();
        String problem = checkConfiguration();
        if (problem != null) {
            backupOperation.status = 400;
            backupOperation.title = "PostgreSQL is not configured";
            backupOperation.message = problem;
            return backupOperation;
        }
        Path target = backupFile(backupId);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        List<String> command = backupCommand(partial);
        operationLog.info(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "Start the PostgreSQL backup: " + String.join(" ", command));
        runningBackups.put(backupId, CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(target.getParent());
                run(command);
                // The file appears in the list only when it is complete
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | InterruptedException e) {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException ignored) {
                    // nothing more to do
                }
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
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
            backupOperation.title = "No PostgreSQL backup in progress for " + backupId;
            return backupOperation;
        }
        try {
            future.get();
            operationLog.info(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "PostgreSQL backup " + backupId + " completed: " + backupFile(backupId));
            backupOperation.status = 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            backupOperation.status = 500;
            backupOperation.title = "PostgreSQL backup interrupted";
        } catch (ExecutionException e) {
            backupOperation.status = 500;
            backupOperation.title = "PostgreSQL backup " + backupId + " failed";
            backupOperation.message = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            operationLog.error(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, backupOperation.title + ": " + backupOperation.message);
        }
        return backupOperation;
    }

    /* ******************************************************************** */
    /*  List                                                                */
    /* ******************************************************************** */

    /**
     * The files camunda_{backupId}.dump of the backup directory
     */
    @Override
    public List<BackupInfo> getListBackups() throws OperationException {
        List<BackupInfo> listBackups = new ArrayList<>();
        if (!isActive() || backupDirectory == null || backupDirectory.isBlank()) {
            return listBackups;
        }
        Path directory = Path.of(backupDirectory);
        if (!Files.isDirectory(directory)) {
            return listBackups;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Long backupId = parseBackupId(file.getFileName().toString());
                if (backupId == null) {
                    continue;
                }
                BackupInfo backupInfo = new BackupInfo();
                backupInfo.backupId = backupId;
                backupInfo.components.add(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE);
                backupInfo.status = BackupInfo.Status.COMPLETED;
                LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()), ZoneId.systemDefault());
                backupInfo.backupTime = time;
                listBackups.add(backupInfo);
            }
        } catch (IOException e) {
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "Can't read the PostgreSQL backups",
                    "Directory [" + backupDirectory + "]: " + e.getMessage());
        }
        return listBackups;
    }

    @Override
    public String getUrlListBackup() {
        return backupDirectory;
    }

    /* ******************************************************************** */
    /*  Restore                                                             */
    /* ******************************************************************** */

    @Override
    public boolean isDatabase() {
        return true;
    }

    @Override
    public boolean supportsRestore() {
        return true;
    }

    /**
     * The backup file exists
     */
    @Override
    public void checkRestorable(long backupId, OperationLog operationLog) throws OperationException {
        String problem = checkConfiguration();
        if (problem != null) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400, "PostgreSQL is not configured", problem);
        }
        Path file = backupFile(backupId);
        if (!Files.isRegularFile(file)) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 404, "PostgreSQL backup not found",
                    "The file " + file + " does not exist.");
        }
        operationLog.info(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "PostgreSQL backup file: " + file);
    }

    /**
     * Restore the database from the backup with pg_restore. DESTRUCTIVE: the objects of the dump replace the objects of the
     * database (--clean). It is done in one transaction: when it fails, the database is unchanged.
     */
    @Override
    public void restore(long backupId, OperationLog operationLog) throws OperationException {
        checkRestorable(backupId, operationLog);
        List<String> command = restoreCommand(backupFile(backupId));
        operationLog.info(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "Restore PostgreSQL: " + String.join(" ", command));
        try {
            run(command);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 500, "PostgreSQL restore failed",
                    "Restore of [" + database + "] from backup " + backupId + ": " + e.getMessage());
        }
    }

    /* ******************************************************************** */
    /*  Commands                                                            */
    /* ******************************************************************** */

    Path backupFile(long backupId) {
        return Path.of(backupDirectory, FILE_PREFIX + backupId + FILE_SUFFIX);
    }

    /**
     * pg_dump -h host -p port -U user -d database --format=custom --no-owner -f file
     */
    List<String> backupCommand(Path file) {
        List<String> command = new ArrayList<>();
        command.add(pgDump);
        addConnection(command);
        command.add("--format=custom");
        command.add("--no-owner");
        command.add("--file=" + file);
        return command;
    }

    /**
     * pg_restore -h host -p port -U user -d database --clean --if-exists --no-owner --single-transaction file
     */
    List<String> restoreCommand(Path file) {
        List<String> command = new ArrayList<>();
        command.add(pgRestore);
        addConnection(command);
        command.add("--clean");
        command.add("--if-exists");
        command.add("--no-owner");
        command.add("--single-transaction");
        command.add(file.toString());
        return command;
    }

    private void addConnection(List<String> command) {
        command.add("--host=" + host);
        command.add("--port=" + port);
        command.add("--username=" + username);
        command.add("--dbname=" + database);
        // The password is in the environment (PGPASSWORD), not here
        command.add("--no-password");
    }

    static Long parseBackupId(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher matcher = BACKUP_FILE.matcher(fileName);
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : null;
    }

    /**
     * @return null if the configuration is complete, else what is missing
     */
    String checkConfiguration() {
        if (database == null || !SAFE_NAME.matcher(database).matches()) {
            return "blueberry.postgres.database is not set, or is not a simple name: [" + database + "]";
        }
        if (username == null || !SAFE_NAME.matcher(username).matches()) {
            return "blueberry.postgres.username is not set, or is not a simple name: [" + username + "]";
        }
        if (host == null || !SAFE_NAME.matcher(host).matches()) {
            return "blueberry.postgres.host is not set, or is not a simple name: [" + host + "]";
        }
        if (backupDirectory == null || backupDirectory.isBlank()) {
            return "blueberry.postgres.backupDirectory is not set (a directory of the machine where Blueberry runs)";
        }
        return null;
    }

    /**
     * Run a program, wait for its end. The output is kept for the message of the error.
     */
    private void run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (password != null && !password.isEmpty()) {
            builder.environment().put("PGPASSWORD", password);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Can't run [" + command.get(0) + "]. Install the PostgreSQL client (pg_dump, pg_restore) where Blueberry runs, "
                    + "or set blueberry.postgres.pgDump and blueberry.postgres.pgRestore. " + e.getMessage(), e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            String shortOutput = output.length() > MAX_OUTPUT ? output.substring(0, MAX_OUTPUT) + "..." : output;
            logger.error("{} exit {}: {}", command.get(0), exit, shortOutput);
            throw new IOException(command.get(0) + " exit code " + exit + ": " + shortOutput.trim());
        }
    }
}
