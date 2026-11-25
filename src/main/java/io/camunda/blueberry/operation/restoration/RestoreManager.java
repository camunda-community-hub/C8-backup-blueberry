package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.ZeebeRestApi;
import io.camunda.blueberry.connect.secondary.ElasticsearchRepositories;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.operation.backup.BackupCompleteness;
import io.camunda.blueberry.operation.backup.BackupManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RestoreManager {

    private final BackupManager backupManager;
    private final BlueberryConfig blueberryConfig;
    private final ZeebeRestApi zeebeRestApi;
    private final SecondaryStorageFactory secondaryStorageFactory;
    private final OptimizeConnect optimizeConnect;
    private final ElasticsearchRepositories repositories;
    Logger logger = LoggerFactory.getLogger(RestoreManager.class);
    private RestoreJob currentRestoreJob;

    public RestoreManager(BackupManager backupManager,
                          BlueberryConfig blueberryConfig,
                          ZeebeRestApi zeebeRestApi,
                          SecondaryStorageFactory secondaryStorageFactory,
                          OptimizeConnect optimizeConnect,
                          ElasticsearchRepositories repositories) {
        this.backupManager = backupManager;
        this.blueberryConfig = blueberryConfig;
        this.zeebeRestApi = zeebeRestApi;
        this.secondaryStorageFactory = secondaryStorageFactory;
        this.optimizeConnect = optimizeConnect;
        this.repositories = repositories;
    }

    public RestorePreflight.Result preflight(long backupId) throws OperationException {
        List<BackupCompleteness.Detail> details = backupManager.getBackupCompletenessDetails();
        return RestorePreflight.evaluate(backupId, details);
    }

    /**
     * blueberry.restore.execution: the configuration allows the live restore
     */
    public boolean isExecutionConfigured() {
        return blueberryConfig.isRestoreExecutionEnabled();
    }

    public boolean isLiveRestoreEnabled() {
        return blueberryConfig.isRestoreExecutionEnabled();
    }

    public synchronized void startRestoration(long backupId) throws OperationException {
        if (!blueberryConfig.isRestoreExecutionEnabled()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400,
                    "Restore disabled",
                    "Live restore is off. Set blueberry.restore.execution=true to let Blueberry restore backup " + backupId
                            + ": the cluster is put in recovery mode, the data is deleted and restored.");
        }
        if (currentRestoreJob != null
                && currentRestoreJob.getJobStatus() == RestoreJob.JOBSTATUS.INPROGRESS) {
            throw new OperationException(null, 409, "Restore in progress",
                    "Restore already in progress for backup " + currentRestoreJob.getBackupId());
        }

        RestorePreflight.Result preflight = preflight(backupId);
        if (!preflight.allowed()) {
            throw new OperationException(null, 400, "Restore not allowed", preflight.message());
        }

        // The names of the repositories are read now, while Optimize answers: the restore stops it. Same rule as the backup
        // (the actuator, else the configuration)
        repositories.refresh();

        RestoreJob job = new RestoreJob(optimizeConnect, zeebeRestApi, secondaryStorageFactory, new OperationLog(),
                2000, blueberryConfig.getRestoreZeebeTimeoutSeconds());
        currentRestoreJob = job;
        Thread thread = new Thread(() -> {
            try {
                job.restore(backupId);
            } catch (Exception e) {
                logger.error("Restore failed for backup {} (consistent: {})", backupId, job.isConsistent(), e);
            }
        });
        thread.setName("blueberry-restore-" + backupId);
        thread.start();
    }

    public RestoreJob getCurrentRestorationJob() {
        return currentRestoreJob;
    }
}
