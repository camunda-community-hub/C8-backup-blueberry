package io.camunda.blueberry.operation.backup;

import io.camunda.blueberry.connect.*;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.operation.OperationLog;

import java.util.ArrayList;
import java.util.List;

public class BackupJob88 {
    private final OptimizeConnect optimizeConnect;
    private final ZeebeConnect zeebeConnect;
    private final ElasticSearchConnect elasticSearchConnect;

    private final OperationLog operationLog;
    private BackupJob.JOBSTATUS jobStatus = BackupJob.JOBSTATUS.PLANNED;
    private long backupId;

    protected BackupJob88(OptimizeConnect optimizeConnect,
                        ZeebeConnect zeebeConnect, ElasticSearchConnect elasticSearchConnect, OperationLog operationLog) {
        this.optimizeConnect = optimizeConnect;
        this.zeebeConnect = zeebeConnect;
        this.elasticSearchConnect = elasticSearchConnect;
        this.operationLog = operationLog;
    }

    public BackupJob.JOBSTATUS getJobStatus() {
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
        operationLog.startOperation("Backup", 7);

        this.jobStatus = BackupJob.JOBSTATUS.INPROGRESS;
        this.backupId = backupId; // calculate a new backup


        // Keep only applications existing in the cluster. Operate and Tasklist are backed up with Zeebe (RDBMS) since 8.10
        List<CamundaApplicationInt> listApplications = List.<CamundaApplicationInt>of(optimizeConnect)
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
            operationLog.operationStep(application.getComponent(), "backup " + application.getComponent().name());


            CamundaApplicationInt.BackupOperation backupOperation = application.backup(backupId, operationLog);
            if (!backupOperation.isOk()) {
                this.jobStatus = BackupJob.JOBSTATUS.FAILED;
                throw new BackupException(application.getComponent(),
                        400,
                        backupOperation.title,
                        backupOperation.message, backupId);
            }
        }

        // Wait end of backup Optimize, Zeebe
        for (CamundaApplicationInt application : listActivesApplication) {
            application.waitBackup(backupId, operationLog);
        }

        // Stop Zeebe imported : force to step 4
        operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, 4, "Pause Zeebe");
        zeebeConnect.pauseExporting(operationLog);

        // backup Zeebe record, only when blueberry.elasticsearch.enabled is true
        if (elasticSearchConnect.isActive()) {
            operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBERECORD, 5, "Backup Zeebe Elasticsearch");
            BackupComponentInt.BackupOperation backupOperation = elasticSearchConnect.backup(backupId, operationLog);
            if (!backupOperation.isOk()) {
                this.jobStatus = BackupJob.JOBSTATUS.FAILED;
                throw new BackupException(CamundaApplicationInt.COMPONENT.ZEEBERECORD,
                        400,
                        backupOperation.title,
                        backupOperation.message, backupId);
            }
            elasticSearchConnect.waitBackup(backupId, operationLog);
        } else {
            operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBERECORD, 5, "Elasticsearch backup disabled (blueberry.elasticsearch.enabled=false), skip it");
        }

        // backup Zeebe
        operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, 6, "Backup Zeebe");
        zeebeConnect.backup(backupId, operationLog);
        zeebeConnect.waitBackup(backupId, operationLog);

        // Finish? Then stop all restoration pod
        operationLog.operationStep(CamundaApplicationInt.COMPONENT.ZEEBE, 7, "Resume Zeebe");
        zeebeConnect.resumeExporting(operationLog);

        operationLog.endOperation();

        jobStatus = BackupJob.JOBSTATUS.COMPLETED;
    }

    public enum JOBSTATUS {PLANNED, INPROGRESS, COMPLETED, FAILED}
}

