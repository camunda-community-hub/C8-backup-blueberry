package io.camunda.blueberry.operation.backup;

import io.camunda.blueberry.connect.*;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.operation.OperationLog;

import java.util.ArrayList;
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

    private static final int NUMBER_OF_STEPS = 8;

    private final OptimizeConnect optimizeConnect;
    private final ZeebeConnect zeebeConnect;
    private final SecondaryStorageFactory secondaryStorageFactory;

    private final OperationLog operationLog;
    private JOBSTATUS jobStatus = JOBSTATUS.PLANNED;
    private long backupId;

    protected BackupJob(OptimizeConnect optimizeConnect,
                        ZeebeConnect zeebeConnect, SecondaryStorageFactory secondaryStorageFactory, OperationLog operationLog) {
        this.optimizeConnect = optimizeConnect;
        this.zeebeConnect = zeebeConnect;
        this.secondaryStorageFactory = secondaryStorageFactory;
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

        // Keep only applications existing in the cluster. Operate and Tasklist are not applications any more:
        // their data is in the secondary storage
        List<CamundaApplicationInt> listApplications = List.<CamundaApplicationInt>of(optimizeConnect)
                .stream()
                .filter(CamundaApplicationInt::exist)
                .toList();

        // The secondary storage object is a component like the others
        SecondaryStorageInt secondaryStorage = secondaryStorageFactory.getSecondaryStorage();
        OrchestrationParameter.Parameter secondaryStorageParameter = secondaryStorageFactory.getValue();
        String secondaryStorageValue = secondaryStorageFactory.getValueString();

        boolean exportingPaused = false;
        try {
            // 1. Soft pause of the exporting
            operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, "Soft pause of Zeebe exporting");
            zeebeConnect.softPauseExporting(backupId, operationLog);
            exportingPaused = true;

            // 2. Start the backup of each application
            List<CamundaApplicationInt> listActivesApplication = new ArrayList<>();
            operationLog.operationStep(null, "Start the backup of "+listActivesApplication.size()+" applications");
            for (CamundaApplicationInt application : listApplications) {
                if (!application.isActive()) {
                    operationLog.info(application.getComponent(), "Application " + application.getComponent().name() + " is not active, skip it");
                    continue;
                }
                listActivesApplication.add(application);
                operationLog.info(application.getComponent(), "backup " + application.getComponent().name());

                checkStarted(application, application.backup(backupId, operationLog));
            }

            // 3. Start the backup of the secondary storage
            operationLog.operationStep(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "Backup-secondary-storage");
            boolean secondaryStorageBackedUp = false;
            if (secondaryStorage != null && secondaryStorage.isActive()) {
                operationLog.info(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "Secondary storage [" + secondaryStorage.getName()
                        + "] (" + secondaryStorageParameter.getOriginLabel() + ")");
                checkStarted(secondaryStorage, secondaryStorage.backup(backupId, operationLog));
                secondaryStorageBackedUp = true;
            } else if (secondaryStorage != null) {
                operationLog.info(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "Secondary storage [" + secondaryStorage.getName()
                        + "] is not active (see the configuration), skip it");
            } else if (!secondaryStorageValue.isEmpty()) {
                // A storage is declared, but nothing can back it up: the backup would be incomplete
                this.jobStatus = JOBSTATUS.FAILED;
                throw new BackupException(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, 400,
                        "No backup for the secondary storage [" + secondaryStorageValue + "]",
                        "Blueberry does not know how to back up the secondary storage [" + secondaryStorageValue
                                + "] (" + secondaryStorageParameter.getOriginLabel() + "). Supported: "
                                + String.join(", ", secondaryStorageFactory.getSupportedNames()), backupId);
            } else {
                operationLog.warning(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "The secondary storage is unknown (not detected, "
                        + "and blueberry.secondaryStorage is not set): it is not backed up");
            }

            // 4. Wait for the end of the applications, then of the secondary storage
            operationLog.operationStep(null, "Wait for the applications");
            for (CamundaApplicationInt application : listActivesApplication) {
                checkFinished(application, application.waitBackup(backupId, operationLog));
            }
            operationLog.operationStep(CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE, "Wait for the secondary storage");
            if (secondaryStorageBackedUp) {
                checkFinished(secondaryStorage, secondaryStorage.waitBackup(backupId, operationLog));
            }

            // 5. Backup of Zeebe, after the secondary storage, as the documentation says
            operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, "Backup Zeebe");
            checkStarted(zeebeConnect, zeebeConnect.backup(backupId, operationLog));
            operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, "Wait for Zeebe");
            checkFinished(zeebeConnect, zeebeConnect.waitBackup(backupId, operationLog));
        } catch (BackupException e) {
            this.jobStatus = JOBSTATUS.FAILED;
            throw e;
        } finally {
            // 6. The exporting must be resumed in all cases
            if (exportingPaused) {
                operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, "Resume Zeebe exporting");
                zeebeConnect.resumeExporting(operationLog);
            }
        }

        operationLog.endOperation();

        jobStatus = JOBSTATUS.COMPLETED;
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
