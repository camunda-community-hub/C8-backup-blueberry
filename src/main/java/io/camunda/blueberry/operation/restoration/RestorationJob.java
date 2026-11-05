package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.platform.version.CamundaVersionProfile;
import io.camunda.blueberry.platform.version.RestorePhase;

import java.util.List;

public class RestorationJob {

    public enum JOBSTATUS {PLANNED, INPROGRESS, REQUIRES_MANUAL_STEPS, TERMINATED, FAILED}

    private final long backupId;
    private final CamundaVersionProfile profile;
    private final OperationLog operationLog;
    private JOBSTATUS jobStatus = JOBSTATUS.PLANNED;

    public RestorationJob(long backupId, CamundaVersionProfile profile) {
        this.backupId = backupId;
        this.profile = profile;
        this.operationLog = new OperationLog();
    }

    public void run(Pwc87RestoreExecutor executor, RestoreLayout layout) {
        jobStatus = JOBSTATUS.INPROGRESS;
        List<RestorePhase> phases = profile.restorePhases();
        operationLog.startOperation("Restore backup " + backupId + " (Camunda " + profile.minorVersion() + ")",
                phases.size());
        try {
            executor.execute(backupId, layout, operationLog);
            operationLog.endOperation();
            jobStatus = JOBSTATUS.TERMINATED;
        } catch (Exception e) {
            operationLog.error(null, e.getMessage());
            jobStatus = JOBSTATUS.FAILED;
        }
    }

    public long getBackupId() {
        return backupId;
    }

    public CamundaVersionProfile getProfile() {
        return profile;
    }

    public OperationLog getOperationLog() {
        return operationLog;
    }

    public JOBSTATUS getJobStatus() {
        return jobStatus;
    }
}
