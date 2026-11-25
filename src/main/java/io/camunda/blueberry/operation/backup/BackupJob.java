package io.camunda.blueberry.operation.backup;

import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.ZeebeConnect;
import io.camunda.blueberry.connect.secondary.ElasticSearchConnect;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.platform.componentconfig.Parameter;

import java.util.List;

/**
 * Collect all operation of the backup. This is returned to the monitoring, or at the end of the execution
 * <p>
 * The backup follows the procedure of the documentation (Camunda 8.8 and over):
 * https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/
 * <ol>
 *   <li>Soft pause of the Zeebe exporting</li>
 *   <li>Start the backup of the applications (Optimize)</li>
 *   <li>Start the backup of the secondary storage. For Elasticsearch: the history backup of Operate and Tasklist,
 *   then the snapshot of the Zeebe records (step 6 of the procedure)</li>
 *   <li>Wait for the applications, and for the secondary storage</li>
 *   <li>Backup of Zeebe (runtime backup), and wait. The documentation does it after the secondary storage</li>
 *   <li>Resume the Zeebe exporting. This is done in all cases, even when the backup fails</li>
 * </ol>
 */
public class BackupJob {

    public static final String STEP_1_SOFT_PAUSE = "Soft pause";
    public static final String STEP_2_BACKUP_SECONDARY_STORAGE = "#1 Backup Secondary Storage";
    public static final String STEP_3_START_OPTIMIZE_BACKUP = "#3 Start Optimize Backup";
    public static final String STEP_4_WAIT_SECONDARY_STORAGE_BACKUP = "#4 Wait secondary storage backup";
    public static final String STEP_5_WAIT_FOR_OPTIMIZE = "#5 Wait for Optimize";
    public static final String STEP_6_BACKUP_ZEEBE_RECORD = "#6 Backup Zeebe Record";
    public static final String STEP_7_WAIT_FOR_THE_BACKUP_ZEEBE_RECORD = "#7 Wait for the Backup Zeebe Record";
    public static final String STEP_8_BACKUP_ZEEBE = "#8 Backup Zeebe";
    public static final String STEP_9_WAIT_ZEEBE_BACKUP = "#9 Wait for Zeebe";
    public static final String STEP_10_RESUME_ZEEBE_EXPORTING = "#10 Resume Zeebe exporting";

    public static final List<String> STEPS = List.of(STEP_1_SOFT_PAUSE, STEP_2_BACKUP_SECONDARY_STORAGE, STEP_3_START_OPTIMIZE_BACKUP,
            STEP_4_WAIT_SECONDARY_STORAGE_BACKUP, STEP_5_WAIT_FOR_OPTIMIZE, STEP_6_BACKUP_ZEEBE_RECORD,
            STEP_7_WAIT_FOR_THE_BACKUP_ZEEBE_RECORD, STEP_8_BACKUP_ZEEBE, STEP_9_WAIT_ZEEBE_BACKUP, STEP_10_RESUME_ZEEBE_EXPORTING);
    public static final int NUMBER_OF_STEPS = STEPS.size();
    /**
     * The steps of the backup, always in this order
     */
    /**
     * The documentation of the backup
     */
    public static final String BACKUP_DOC = "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/backup-and-restore/";

    private final OptimizeConnect optimizeConnect;
    private final ZeebeConnect zeebeConnect;
    private final SecondaryStorageFactory secondaryStorageFactory;
    /**
     * Optimize needs Elasticsearch, even when the secondary storage is a database: the Zeebe records (zeebe-record*) are snapshotted in it
     */
    private final ElasticSearchConnect elasticSearchConnect;

    private final OperationLog operationLog;
    private JOBSTATUS jobStatus = JOBSTATUS.PLANNED;
    private long backupId;

    protected BackupJob(OptimizeConnect optimizeConnect,
                        ZeebeConnect zeebeConnect, SecondaryStorageFactory secondaryStorageFactory,
                        ElasticSearchConnect elasticSearchConnect, OperationLog operationLog) {
        this.optimizeConnect = optimizeConnect;
        this.zeebeConnect = zeebeConnect;
        this.secondaryStorageFactory = secondaryStorageFactory;
        this.elasticSearchConnect = elasticSearchConnect;
        this.operationLog = operationLog;
    }

    public JOBSTATUS getJobStatus() {
        return jobStatus;
    }

    public OperationLog getOperationLog() {
        return operationLog;
    }

    public long getBackupId() {
        return backupId;
    }

    /**
     * Start a backup
     */
    public void backup(long backupId) throws BackupException {
        operationLog.startOperation("Backup", NUMBER_OF_STEPS);

        this.jobStatus = JOBSTATUS.INPROGRESS;
        this.backupId = backupId; // calculate a new backup

        // The secondary storage object is a component like the others
        SecondaryStorageInt secondaryStorage = secondaryStorageFactory.getSecondaryStorage();
        Parameter secondaryStorageParameter = secondaryStorageFactory.getValue();
        String secondaryStorageValue = secondaryStorageFactory.getValueString();

        boolean exportingPaused = false;
        try {
            // 1. Soft pause of the exporting
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_1_SOFT_PAUSE, "Soft pause exporting in Zeebe",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#1-soft-pause-exporting-in-zeebe");
            zeebeConnect.softPauseExporting(backupId, operationLog);
            exportingPaused = true;


            // 2. Start the backup of the secondary storage
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, STEP_2_BACKUP_SECONDARY_STORAGE, " Start the web applications backup (Secondary storage)",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#2-start-the-web-applications-backup-operate--tasklist");
            boolean secondaryStorageBackedUp = false;
            if (secondaryStorage != null && secondaryStorage.isActive()) {
                operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "Secondary storage [" + secondaryStorage.getName()
                        + "] (" + secondaryStorageParameter.getOriginLabel() + ")");
                if (secondaryStorage instanceof ElasticSearchConnect elasticsearch) {
                    // Elasticsearch: the history backup (Operate, Tasklist). The snapshot of the Zeebe records is the step 6
                    checkStarted(secondaryStorage, elasticsearch.backupApplication(backupId, operationLog));
                } else {
                    // a database: its own backup
                    checkStarted(secondaryStorage, secondaryStorage.backup(backupId, operationLog));
                }
                secondaryStorageBackedUp = true;
            } else if (secondaryStorage != null) {
                operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "Secondary storage [" + secondaryStorage.getName()
                        + "] is not active (see the configuration), skip it");
            } else if (!secondaryStorageValue.isEmpty()) {
                // A storage is declared, but nothing can back it up: the backup would be incomplete
                this.jobStatus = JOBSTATUS.FAILED;
                throw new BackupException(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, 400,
                        "No backup for the secondary storage [" + secondaryStorageValue + "]",
                        "Blueberry does not know how to back up the secondary storage [" + secondaryStorageValue
                                + "] (" + secondaryStorageParameter.getOriginLabel() + "). Supported: "
                                + String.join(", ", secondaryStorageFactory.getSupportedNames()), backupId);
            } else {
                operationLog.warning(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "The secondary storage is unknown (not detected, "
                        + "and blueberry.secondaryStorage is not set): it is not backed up");
            }


            // 3. Start the backup of each application
            operationLog.startOperationStep(null, STEP_3_START_OPTIMIZE_BACKUP, "Start Optimize backup",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#3-start-the-optimize-backup");
            if (!optimizeConnect.isActive()) {
                operationLog.info(optimizeConnect.getComponent(), "Optimize is not active, skipped");
            } else {
                checkStarted(optimizeConnect, optimizeConnect.backup(backupId, operationLog));
            }


            // 4. Wait for the end of the applications, then of the secondary storage
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, STEP_4_WAIT_SECONDARY_STORAGE_BACKUP, "Wait the secondary storage [" + secondaryStorage.getName() + "] backup to complete",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#4-wait-for-the-web-applications-backup-to-complete");
            if (secondaryStorageBackedUp) {
                if (secondaryStorage instanceof ElasticSearchConnect elasticsearch) {
                    checkFinished(secondaryStorage, elasticsearch.waitBackupApplication(backupId, operationLog));
                } else {
                    checkFinished(secondaryStorage, secondaryStorage.waitBackup(backupId, operationLog));
                }
            }

            // 5. Wait for optimize
            operationLog.startOperationStep(null, STEP_5_WAIT_FOR_OPTIMIZE, "Wait for the Optimize backup to complete",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#5-wait-for-the-optimize-backup-to-complete");
            if (!optimizeConnect.isActive()) {
                operationLog.info(optimizeConnect.getComponent(), "Optimize is not active, skipped");
            } else {
                checkFinished(optimizeConnect, optimizeConnect.waitBackup(backupId, operationLog));
            }

            // 6.Backup zeebe record
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_6_BACKUP_ZEEBE_RECORD, "Create the Zeebe records backup",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#6-create-a-backup-of-the-exported-zeebe-indices-in-elasticsearchopensearch");
            if (!optimizeConnect.isActive()) {
                operationLog.info(optimizeConnect.getComponent(), "Optimize is not active, skipped");
            } else {
                requireElasticsearchForOptimize();
                checkStarted(elasticSearchConnect, elasticSearchConnect.backupZeebeRecord(backupId, operationLog));
            }

            // 7. Wait for zeebe record
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_7_WAIT_FOR_THE_BACKUP_ZEEBE_RECORD, "Wait for the Zeebe record to be complete",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#7-wait-for-the-backup-of-the-exported-zeebe-indices-to-complete-before-proceeding");
            if (!optimizeConnect.isActive()) {
                operationLog.info(optimizeConnect.getComponent(), "Optimize is not active, skipped");
            } else {
                checkFinished(elasticSearchConnect, elasticSearchConnect.waitZeebeRecord(backupId, operationLog));
            }
            // 8. Backup of Zeebe, after the secondary storage, as the documentation says
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_8_BACKUP_ZEEBE, "Create the Zeebe broker backup",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#8-create-the-zeebe-broker-backup");
            checkStarted(zeebeConnect, zeebeConnect.backup(backupId, operationLog));

            // 9. wait for the zeebe PVC
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_9_WAIT_ZEEBE_BACKUP, "Wait the Zeebe backup to complete",
                    "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#9-wait-for-the-zeebe-backup-to-complete-before-proceeding");
            checkFinished(zeebeConnect, zeebeConnect.waitBackup(backupId, operationLog));
        } catch (BackupException e) {
            this.jobStatus = JOBSTATUS.FAILED;
            throw e;
        } finally {
            // 10. The exporting must be resumed in all cases
            if (exportingPaused) {
                // We want this step is the last one, even if we stop during the execution
                operationLog.setForceStep(NUMBER_OF_STEPS - 1);
                operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_10_RESUME_ZEEBE_EXPORTING, " Resume exporting in Zeebe",
                        "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/elasticsearch/es-backup/#10-resume-exporting-in-zeebe");
                zeebeConnect.resumeExporting(operationLog);
            }
        }

        operationLog.endOperation();

        jobStatus = JOBSTATUS.COMPLETED;
    }

    /**
     * Optimize reads the Zeebe records in Elasticsearch, even when the secondary storage is a database: Elasticsearch must be configured
     * (blueberry.elasticsearch.enabled and blueberry.elasticsearchurl), else the backup would not contain the Zeebe records
     */
    private void requireElasticsearchForOptimize() throws BackupException {
        if (!elasticSearchConnect.isPresent()) {
            this.jobStatus = JOBSTATUS.FAILED;
            throw new BackupException(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, 400,
                    "Elasticsearch is not configured",
                    "Optimize is active and needs Elasticsearch for the snapshot of the Zeebe records: set blueberry.elasticsearch.enabled and blueberry.elasticsearchurl", backupId);
        }
    }

    /**
     * The backup is started: if not, the job is in failure
     */
    private void checkStarted(BackupComponentInt component, BackupComponentInt.BackupOperation backupOperation) throws BackupException {
        if (!backupOperation.isOk()) {
            this.jobStatus = JOBSTATUS.FAILED;
            throw new BackupException(component.getComponent(),
                    400,
                    backupOperation.title,
                    backupOperation.message, backupId);
        }
    }

    /**
     * The backup of a component is finished. A component which answers a status 4xx or 5xx says its backup failed.
     */
    private void checkFinished(BackupComponentInt component, BackupComponentInt.BackupOperation backupOperation) throws BackupException {
        if (backupOperation != null && backupOperation.status >= 400) {
            this.jobStatus = JOBSTATUS.FAILED;
            throw new BackupException(component.getComponent(),
                    backupOperation.status,
                    backupOperation.title,
                    backupOperation.message, backupId);
        }
    }

    public enum JOBSTATUS {PLANNED, INPROGRESS, COMPLETED, FAILED}
}

