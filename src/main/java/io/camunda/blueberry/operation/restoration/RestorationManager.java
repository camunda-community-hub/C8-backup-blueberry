package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.backup.BackupCompleteness;
import io.camunda.blueberry.operation.backup.BackupManager;
import io.camunda.blueberry.platform.version.CamundaVersionProfile;
import io.camunda.blueberry.platform.version.CamundaVersionService;
import io.camunda.blueberry.platform.version.RestorePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RestorationManager {

    private final BackupManager backupManager;
    private final CamundaVersionService versionService;
    private final BlueberryConfig blueberryConfig;
    private final RestoreApiExecutor restoreApiExecutor;
    private RestorationJob currentRestorationJob;
    Logger logger = LoggerFactory.getLogger(RestorationManager.class);

    public RestorationManager(BackupManager backupManager,
                              CamundaVersionService versionService,
                              BlueberryConfig blueberryConfig,
                              RestoreApiExecutor restoreApiExecutor) {
        this.backupManager = backupManager;
        this.versionService = versionService;
        this.blueberryConfig = blueberryConfig;
        this.restoreApiExecutor = restoreApiExecutor;
    }

    /**
     * The version Blueberry restores: Camunda 8.10, with the Restore API (REST only, no Kubernetes). For the other versions,
     * the restore is described by the documentation.
     */
    static boolean canRestore(String minorVersion) {
        return "8.10".equals(minorVersion);
    }

    public RestorePreflight.Result preflight(long backupId) throws OperationException {
        List<BackupCompleteness.Detail> details = backupManager.getBackupCompletenessDetails();
        return RestorePreflight.evaluate(backupId, details, versionService.getActiveProfile());
    }

    public List<RestorePhase> phasesForActiveVersion() {
        return versionService.getActiveProfile().restorePhases();
    }

    public boolean isLiveRestoreEnabled() {
        return blueberryConfig.isRestoreExecutionEnabled()
                && canRestore(versionService.getActiveProfile().minorVersion());
    }

    public synchronized void startRestoration(long backupId) throws OperationException {
        CamundaVersionProfile profile = versionService.getActiveProfile();
        if (!canRestore(profile.minorVersion())) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400,
                    "Restore version",
                    "Blueberry restores Camunda 8.10 with the Restore API (REST only). Select Camunda 8.10. For "
                            + profile.minorVersion() + ", follow the documentation: " + profile.docsRestoreUrl());
        }
        if (!blueberryConfig.isRestoreExecutionEnabled()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400,
                    "Restore disabled",
                    "Live restore is off. Set blueberry.restore.execution=true to let Blueberry restore backup " + backupId
                            + ": the cluster is put in recovery mode, the data is deleted and restored.");
        }
        if (currentRestorationJob != null
                && currentRestorationJob.getJobStatus() == RestorationJob.JOBSTATUS.INPROGRESS) {
            throw new OperationException(null, 409, "Restore in progress",
                    "Restore already in progress for backup " + currentRestorationJob.getBackupId());
        }

        RestorePreflight.Result preflight = preflight(backupId);
        if (!preflight.allowed()) {
            throw new OperationException(null, 400, "Restore not allowed", preflight.message());
        }

        RestorationJob job = new RestorationJob(backupId, profile);
        currentRestorationJob = job;
        Thread thread = new Thread(() -> {
            try {
                job.run(restoreApiExecutor);
            } catch (Exception e) {
                logger.error("Restore failed for backup {}", backupId, e);
            }
        });
        thread.setName("blueberry-restore-" + backupId);
        thread.start();
    }

    public RestorationJob getCurrentRestorationJob() {
        return currentRestorationJob;
    }
}
