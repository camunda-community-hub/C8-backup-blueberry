package io.camunda.blueberry.operation.backup;

import io.camunda.blueberry.connect.*;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.operation.OperationLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Collect all operation of the backup. This is returned to the monitoring, or at the end of the execution
 */
public class BackupJob {

    private final OperateConnect operateConnect;
    private final TaskListConnect taskListConnect;
    private final OptimizeConnect optimizeConnect;
    private final ZeebeConnect zeebeConnect;
    private final ElasticSearchConnect elasticSearchConnect;

    private final OperationLog operationLog;
    private JOBSTATUS jobStatus = JOBSTATUS.PLANNED;
    private long backupId;
    // Component currently processed: reported if the backup fails
    private CamundaApplicationInt.COMPONENT currentComponent = CamundaApplicationInt.COMPONENT.ZEEBE;
    // True when the exporting is paused: it must be resumed if the backup fails
    private boolean exportingPaused = false;

    protected BackupJob(OperateConnect operateConnect, TaskListConnect taskListConnect, OptimizeConnect optimizeConnect,
                        ZeebeConnect zeebeConnect, ElasticSearchConnect elasticSearchConnect, OperationLog operationLog) {
        this.operateConnect = operateConnect;
        this.taskListConnect = taskListConnect;
        this.optimizeConnect = optimizeConnect;
        this.zeebeConnect = zeebeConnect;
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
     * Start a backup. Whatever the error, the job moves to FAILED and the error is added to the operation log,
     * so the backup never stays INPROGRESS
     */
    public void backup(long backupId) throws BackupException {
        try {
            runBackup(backupId);
        } catch (BackupException e) {
            failBackup(e.getComponent() == null ? currentComponent : e.getComponent(), e.getError() + " : " + e.getMessage());
            throw e;
        } catch (Exception e) {
            BackupException backupException = BackupException.getInstanceFromException(currentComponent, backupId, e);
            failBackup(currentComponent, backupException.getError() + " : " + backupException.getMessage());
            throw backupException;
        }
    }

    /**
     * The backup failed: status FAILED, a new ERROR record in the log, and exporting resumed if it was paused
     */
    private void failBackup(CamundaApplicationInt.COMPONENT component, String message) {
        this.jobStatus = JOBSTATUS.FAILED;
        operationLog.error(component, "Backup [" + backupId + "] failed: " + message);
        if (exportingPaused) {
            try {
                zeebeConnect.resumeExporting(operationLog);
                exportingPaused = false;
            } catch (Exception e) {
                operationLog.error(CamundaApplicationInt.COMPONENT.ZEEBE, "Can't resume Zeebe exporting after the failure: " + e.getMessage());
            }
        }
    }

    private void runBackup(long backupId) throws BackupException {
        operationLog.startOperation("Backup", 7);

        this.jobStatus = JOBSTATUS.INPROGRESS;
        this.backupId = backupId; // calculate a new backup


        // Keep only applications existing in the cluster
        List<CamundaApplicationInt> listApplications = List.of(operateConnect, taskListConnect, optimizeConnect)
                .stream()
                .filter(CamundaApplicationInt::exist)
                .toList();


        // For each application, start the backup
        List<CamundaApplicationInt> listActivesApplication = new ArrayList<>();
        for (CamundaApplicationInt application : listApplications) {
            if (!application.isActive()) {
                operationLog.operationStep(application.getComponent(), "Application " + application.getComponent().name() + " is not active, skip it");
                continue;
            }
            listActivesApplication.add(application);
            currentComponent = application.getComponent();
            operationLog.operationStep(application.getComponent(), "backup " + application.getComponent().name());


            CamundaApplicationInt.BackupOperation backupOperation = application.backup(backupId, operationLog);
            if (!backupOperation.isOk()) {
                this.jobStatus = JOBSTATUS.FAILED;
                throw new BackupException(application.getComponent(),
                        400,
                        backupOperation.title,
                        backupOperation.message, backupId);
            }
        }

        // Wait end of backup Operate, TaskList, Optimize, Zeebe
        for (CamundaApplicationInt application : listActivesApplication) {
            currentComponent = application.getComponent();
            application.waitBackup(backupId, operationLog);
        }

        // Stop Zeebe imported : force to step 4
        currentComponent = CamundaApplicationInt.COMPONENT.ZEEBE;
        operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, 4, "Pause Zeebe");
        zeebeConnect.pauseExporting(operationLog);
        exportingPaused = true;

        // backup Zeebe record
        currentComponent = CamundaApplicationInt.COMPONENT.ZEEBERECORD;
        operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBERECORD, 5, "Backup Zeebe Elasticsearch");
        BackupComponentInt.BackupOperation backupOperation = elasticSearchConnect.backup(backupId, operationLog);
        if (!backupOperation.isOk()) {
            this.jobStatus = JOBSTATUS.FAILED;
            throw new BackupException(CamundaApplicationInt.COMPONENT.ZEEBERECORD,
                    400,
                    backupOperation.title,
                    backupOperation.message, backupId);
        }
        elasticSearchConnect.waitBackup(backupId, operationLog);

        // backup Zeebe
        currentComponent = CamundaApplicationInt.COMPONENT.ZEEBE;
        operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, 6, "Backup Zeebe");
        zeebeConnect.backup(backupId, operationLog);
        zeebeConnect.waitBackup(backupId, operationLog);

        // Finish? Then stop all restoration pod
        operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, 7, "Resume Zeebe");
        zeebeConnect.resumeExporting(operationLog);
        exportingPaused = false;

        operationLog.endOperation();

        jobStatus = JOBSTATUS.COMPLETED;
    }

    public enum JOBSTATUS {PLANNED, INPROGRESS, COMPLETED, FAILED}
}
