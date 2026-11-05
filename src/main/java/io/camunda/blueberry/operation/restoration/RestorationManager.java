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
    private final Pwc87RestoreExecutor restoreExecutor;
    private RestorationJob currentRestorationJob;
    Logger logger = LoggerFactory.getLogger(RestorationManager.class);

    public RestorationManager(BackupManager backupManager,
                              CamundaVersionService versionService,
                              BlueberryConfig blueberryConfig,
                              Pwc87RestoreExecutor restoreExecutor) {
        this.backupManager = backupManager;
        this.versionService = versionService;
        this.blueberryConfig = blueberryConfig;
        this.restoreExecutor = restoreExecutor;
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
                && "8.7".equals(versionService.getActiveProfile().minorVersion());
    }

    public synchronized void startRestoration(long backupId) throws OperationException {
        CamundaVersionProfile profile = versionService.getActiveProfile();
        if (!"8.7".equals(profile.minorVersion())) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400,
                    "Restore version",
                    "Automated restore follows the PwC 8.7 runbook. Select Camunda 8.7.");
        }
        if (!blueberryConfig.isRestoreExecutionEnabled()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400,
                    "Restore disabled",
                    "Live restore is off. Set blueberry.restore.execution=true to stop the cluster, delete Camunda indices, delete Zeebe volumes, and restore backup " + backupId + ".");
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
        RestoreLayout layout = RestoreLayout.from(blueberryConfig);

        Thread thread = new Thread(() -> {
            try {
                job.run(restoreExecutor, layout);
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
