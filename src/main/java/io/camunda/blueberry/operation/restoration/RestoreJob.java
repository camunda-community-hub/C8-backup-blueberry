package io.camunda.blueberry.operation.restoration;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.ZeebeRestApi;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Collect all operation of the restore. This is returned to the monitoring, or at the end of the execution
 * <p>
 * The restore follows the procedure of the documentation (Camunda 8.8 and over, the Restore API):
 * https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/backup-and-restore/
 * <ol>
 *   <li>Stop Optimize: its indices are deleted and restored with the others (Kubernetes or Docker, see ClusterConnect)</li>
 *   <li>Validate: the Zeebe backup is complete, the secondary storage has the backup</li>
 *   <li>PATCH /v2/mode?mode=RECOVERING: the brokers stop processing</li>
 *   <li>Restore the secondary storage (the cluster stays in recovery mode):
 *     <ul><li>Elasticsearch: dry run of the restore of Zeebe, delete the indices, restore the snapshots one by one</li>
 *     <li>A database: restore with the native tool of the database</li></ul></li>
 *   <li>POST /v2/restore {"backupIds": [id]} (after a dry run for a database), wait with GET /v2/restore</li>
 *   <li>The cluster leaves the recovery mode itself: check GET /v2/topology, then start Optimize</li>
 * </ol>
 * <p>
 * <b>Consistency</b>: {@link #isConsistent()} is true as long as the platform is in a state that works: unchanged, or restored.
 * It becomes false when the data starts to be replaced (the secondary storage is deleted), and true again when the restore of
 * Zeebe is finished. If the restore fails while it is false, the platform holds a mix of the current data and of the backup:
 * Optimize stays stopped, the cluster stays in recovery mode, and the last message of the log is an error that says so.
 * If it fails while it is true, Optimize is started again, and the cluster goes back to processing when nothing was changed.
 */
public class RestoreJob {

    public static final String MODE_RECOVERING = "RECOVERING";
    public static final String MODE_PROCESSING = "PROCESSING";
    public static final String STEP_1_VALIDATE = "#1 Validate";
    public static final String STEP_2_STOP_OPTIMIZE = "#2 Stop Optimize";
    public static final String STEP_3_RECOVERY_MODE = "#3 Recovery mode";
    public static final String STEP_4_RESTORE_SECONDARY_STORAGE = "#4 Restore secondary storage";
    public static final String STEP_5_RESTORE_ZEEBE = "#5 Restore Zeebe";
    public static final String STEP_6_WAIT_PROCESSING = "#6 Wait for processing";
    /**
     * The steps of the restore, always in this order
     */
    public static final List<String> STEPS = List.of(STEP_1_VALIDATE, STEP_2_STOP_OPTIMIZE, STEP_3_RECOVERY_MODE,
            STEP_4_RESTORE_SECONDARY_STORAGE, STEP_5_RESTORE_ZEEBE, STEP_6_WAIT_PROCESSING);
    public static final int NUMBER_OF_STEPS = STEPS.size();
    /**
     * The documentation of the restore
     */
    public static final String RESTORE_DOC = "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/backup-and-restore/";
    private final OptimizeConnect optimizeConnect;
    private final ZeebeRestApi rest;
    private final SecondaryStorageFactory secondaryStorageFactory;
    private final OperationLog operationLog;
    private final long pollMillis;
    private final int timeoutSeconds;
    private JOBSTATUS jobStatus = JOBSTATUS.PLANNED;
    private long backupId;
    /**
     * The marker of the consistency of the platform, see the description of the class
     */
    private boolean consistent = true;
    protected RestoreJob(OptimizeConnect optimizeConnect, ZeebeRestApi rest, SecondaryStorageFactory secondaryStorageFactory,
                         OperationLog operationLog, long pollMillis, int timeoutSeconds) {
        this.optimizeConnect = optimizeConnect;
        this.rest = rest;
        this.secondaryStorageFactory = secondaryStorageFactory;
        this.operationLog = operationLog;
        this.pollMillis = pollMillis;
        this.timeoutSeconds = timeoutSeconds;
    }

    static long changeId(ZeebeRestApi.RestResult result) {
        if (result.body() != null && result.body().has("changeId")) {
            return result.body().path("changeId").asLong(-1);
        }
        return -1;
    }

    /**
     * Every partition of the topology (brokers[].partitions[]) says RECOVERING
     */
    static boolean allPartitionsRecovering(JsonNode topology) {
        List<JsonNode> partitions = partitions(topology);
        return !partitions.isEmpty() && partitions.stream().allMatch(RestoreJob::isRecovering);
    }

    static boolean anyPartitionRecovering(JsonNode topology) {
        return partitions(topology).stream().anyMatch(RestoreJob::isRecovering);
    }

    private static List<JsonNode> partitions(JsonNode topology) {
        List<JsonNode> partitions = new ArrayList<>();
        if (topology == null) {
            return partitions;
        }
        for (JsonNode broker : topology.path("brokers")) {
            for (JsonNode partition : broker.path("partitions")) {
                partitions.add(partition);
            }
        }
        return partitions;
    }

    /**
     * One of the values of the partition is RECOVERING (the name of the field is not fixed by the documentation)
     */
    private static boolean isRecovering(JsonNode partition) {
        for (JsonNode value : partition) {
            if (value.isTextual() && "RECOVERING".equalsIgnoreCase(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node.path(name).isTextual()) {
                return node.path(name).asText();
            }
        }
        return "";
    }

    /* ******************************************************************** */
    /*  Tools                                                               */
    /* ******************************************************************** */

    private static OperationException fail(int status, String title, String message) {
        return new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, status, title, message);
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
     * @return false when the data of the platform is partly replaced: the platform holds a mix of the current data and of the backup
     */
    public boolean isConsistent() {
        return consistent;
    }

    /**
     * Start a restore. It ends when the cluster is back, or throws the cause of the failure (after the failure is logged)
     */
    public void restore(long backupId) throws OperationException {
        operationLog.startOperation("Restore backup " + backupId, NUMBER_OF_STEPS);
        this.jobStatus = JOBSTATUS.INPROGRESS;
        this.backupId = backupId;
        this.consistent = true;

        // What was done: the failure handling depends on it
        SecondaryStorageInt secondaryStorage = null;
        int optimizeReplicas = -1; // replicas of Optimize before it was stopped. -1: Optimize was not stopped (not in the cluster)
        boolean optimizeStarted = false;
        boolean recoveryModeRequested = false;
        boolean zeebeRestoreStarted = false;
        boolean zeebeRestoreFinished = false;

        try {


            // 1. Validate the backup and the cluster
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_1_VALIDATE, "Validate the backup and the cluster", RESTORE_DOC);
            ZeebeRestApi.RestResult topology = rest.getTopology();
            if (!topology.isSuccess()) {
                throw fail(503, "The REST API of the cluster does not answer",
                        "GET " + rest.getBaseUrl() + "/topology answered " + topology.status() + " " + topology.raw()
                                + ". The Restore API exists from Camunda 8.10 (/v2/mode, /v2/restore). "
                                + "Check blueberry.zeebeRestUrl (or camunda.client.zeebe.restAddress) and the credentials.");
            }
            operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "The REST API answers: " + rest.getBaseUrl());

            ZeebeRestApi.RestResult backup = rest.getRuntimeBackup(backupId);
            if (!backup.isSuccess() || backup.body() == null) {
                throw fail(404, "Zeebe backup not found", "GET " + rest.getBaseUrl() + "/backups/runtime/" + backupId
                        + " answered " + backup.status() + " " + backup.raw());
            }
            String backupState = backup.body().path("state").asText();
            if (!"COMPLETED".equalsIgnoreCase(backupState)) {
                throw fail(400, "Zeebe backup not complete", "The Zeebe backup " + backupId + " is " + backupState + ", not COMPLETED."
                        + (backup.body().path("failureReason").isTextual() ? " " + backup.body().path("failureReason").asText() : ""));
            }
            operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "The Zeebe backup " + backupId + " is COMPLETED");

            secondaryStorage = secondaryStorageFactory.getSecondaryStorage();
            if (secondaryStorage == null) {
                throw fail(400, "Secondary storage unknown", "The secondary storage is not detected on the cluster, and blueberry.secondaryStorage is not set "
                        + "(or has no implementation). Supported: " + String.join(", ", secondaryStorageFactory.getSupportedNames()));
            }
            if (!secondaryStorage.supportsRestore()) {
                throw fail(400, "Restore not available", "Blueberry can't restore the secondary storage [" + secondaryStorage.getName() + "].");
            }
            secondaryStorage.checkRestorable(backupId, operationLog);
            operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "The backup " + backupId + " exists in the secondary storage ["
                    + secondaryStorage.getName() + "]");


            // 2. Stop Optimize: its indices are deleted and restored with the others
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.OPTIMIZE, STEP_2_STOP_OPTIMIZE, "Stop Optimize", RESTORE_DOC);
            if (!optimizeConnect.isActive()) {
                operationLog.info(optimizeConnect.getComponent(), "Optimize is not in the cluster: nothing to stop");
            } else {
                optimizeReplicas = optimizeConnect.stop(operationLog);
            }


            // 3. Recovery mode: the brokers stop processing
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_3_RECOVERY_MODE, "Enter the recovery mode", RESTORE_DOC);
            recoveryModeRequested = true;
            ZeebeRestApi.RestResult mode = rest.changeMode(MODE_RECOVERING);
            if (!mode.isSuccess()) {
                throw fail(mode.status(), "Can't enter the recovery mode",
                        "PATCH " + rest.getBaseUrl() + "/mode?mode=RECOVERING answered " + mode.status() + " " + mode.raw());
            }
            long recoveryChangeId = changeId(mode);
            operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "Recovery mode requested, change " + recoveryChangeId);
            // A restore sent before every broker is in recovery mode is refused (409): wait for it
            waitUntil("the recovery mode of all the partitions", () -> {
                ZeebeRestApi.RestResult recoveryTopology = rest.getTopology();
                return (recoveryTopology.isSuccess() && allPartitionsRecovering(recoveryTopology.body())) || changeApplied(recoveryChangeId);
            });
            operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "The cluster is in recovery mode");


            // 4. Restore the secondary storage
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, STEP_4_RESTORE_SECONDARY_STORAGE,
                    "Restore the secondary storage [" + secondaryStorage.getName() + "]", RESTORE_DOC);
            if (!secondaryStorage.isDatabase()) {
                // Elasticsearch: nothing is deleted before the restore of Zeebe is known to be possible
                ZeebeRestApi.RestResult dryRun = rest.restore(List.of(backupId), true);
                if (!dryRun.isSuccess()) {
                    throw fail(dryRun.status() == 0 ? 500 : dryRun.status(), "The restore of Zeebe is refused",
                            "POST " + rest.getBaseUrl() + "/restore?dryRun=true answered " + dryRun.status() + " " + dryRun.raw()
                                    + ". Nothing is restored.");
                }
                operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "Dry run of the restore of Zeebe: accepted (" + dryRun.status() + ")");
            }
            // From here the data is replaced: the platform is not consistent until Zeebe is restored too
            consistent = false;
            secondaryStorage.restore(backupId, operationLog);

            // 5. Restore Zeebe
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_5_RESTORE_ZEEBE, "Restore Zeebe", RESTORE_DOC);
            if (secondaryStorage.isDatabase()) {
                // The restore of Zeebe reads the exporter position in the restored database
                ZeebeRestApi.RestResult dryRun = rest.restore(List.of(backupId), true);
                if (!dryRun.isSuccess()) {
                    throw fail(dryRun.status() == 0 ? 500 : dryRun.status(), "The restore of Zeebe is refused",
                            "POST " + rest.getBaseUrl() + "/restore?dryRun=true answered " + dryRun.status() + " " + dryRun.raw());
                }
                operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "Dry run of the restore of Zeebe: accepted (" + dryRun.status() + ")");
            }
            zeebeRestoreStarted = true;
            ZeebeRestApi.RestResult restore = rest.restore(List.of(backupId), false);
            if (!restore.isSuccess()) {
                throw fail(restore.status() == 0 ? 500 : restore.status(), "The restore of Zeebe is refused",
                        "POST " + rest.getBaseUrl() + "/restore answered " + restore.status() + " " + restore.raw());
            }
            long restoreChangeId = changeId(restore);
            operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "Restore of Zeebe started, change " + restoreChangeId);

            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            String lastStatus = "";
            while (!zeebeRestoreFinished) {
                if (System.currentTimeMillis() >= deadline) {
                    throw fail(504, "The restore of Zeebe is too long", "The restore is not finished after " + timeoutSeconds
                            + " seconds (blueberry.restore.zeebeRestoreTimeoutSeconds). Last status: " + lastStatus);
                }
                ZeebeRestApi.RestResult status = rest.getRestoreStatus();
                if (status.status() == 404) {
                    // The API gives no status when the restore is finished
                    operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "Restore of Zeebe finished (GET /restore: 404)"
                            + (changeApplied(restoreChangeId) ? ", change " + restoreChangeId + " applied" : ""));
                    zeebeRestoreFinished = true;
                } else if (status.isSuccess() && status.body() != null) {
                    String state = text(status.body(), "status", "state");
                    if (!state.equals(lastStatus)) {
                        operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "Restore of Zeebe: " + state);
                        lastStatus = state;
                    }
                    if ("COMPLETED".equalsIgnoreCase(state)) {
                        zeebeRestoreFinished = true;
                    } else if ("FAILED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                        throw fail(500, "The restore of Zeebe is " + state, "GET " + rest.getBaseUrl() + "/restore: " + status.raw()
                                + ". Fix the cause (the partitions try again by themselves). If the backup is wrong: DELETE "
                                + "{management}/actuator/cluster/changes/" + restoreChangeId + ", then start the restore again. "
                                + "Do not switch the cluster to PROCESSING after a partial restore.");
                    }
                }
                if (!zeebeRestoreFinished) {
                    sleep();
                }
            }
            // The secondary storage and Zeebe hold the data of the backup: the platform is consistent again
            consistent = true;

            // 6. The cluster leaves the recovery mode itself, then Optimize can start
            operationLog.startOperationStep(BackupComponentInt.COMPONENT.ZEEBE, STEP_6_WAIT_PROCESSING, "Wait for the end of the recovery mode", RESTORE_DOC);
            waitUntil("the end of the recovery mode", () -> {
                ZeebeRestApi.RestResult processingTopology = rest.getTopology();
                return processingTopology.isSuccess() && processingTopology.body() != null && !anyPartitionRecovering(processingTopology.body());
            });
            operationLog.info(BackupComponentInt.COMPONENT.ZEEBE, "The cluster left the recovery mode: " + rest.getBaseUrl() + "/topology");
            if (optimizeReplicas > 0) {
                optimizeConnect.start(optimizeReplicas, operationLog);
                optimizeStarted = true;
            }

            operationLog.endOperation();
            this.jobStatus = JOBSTATUS.TERMINATED;
            operationLog.info(null, "The restore is done and must be validated manually: open Operate and Tasklist, check the data from the backup time, "
                    + "and start one test process.");
        } catch (OperationException | RuntimeException e) {
            this.jobStatus = JOBSTATUS.FAILED;
            operationLog.error(null, e.getMessage());

            if (consistent) {
                // Nothing was changed, or the data is restored: Optimize is started again
                if (optimizeReplicas > 0 && !optimizeStarted) {
                    try {
                        optimizeConnect.start(optimizeReplicas, operationLog);
                    } catch (OperationException startException) {
                        operationLog.error(optimizeConnect.getComponent(), "Optimize could not be started again: " + startException.getMessage());
                    }
                }
                if (zeebeRestoreFinished) {
                    operationLog.warning(null, "The platform is consistent: the data of the backup " + backupId + " is restored. "
                            + "The cluster did not finish to start (see the error above): check the cluster, and start Optimize if it is stopped.");
                } else if (!recoveryModeRequested) {
                    operationLog.warning(null, "Nothing was changed: the platform is consistent.");
                } else {
                    ZeebeRestApi.RestResult back = rest.changeMode(MODE_PROCESSING);
                    if (back.isSuccess()) {
                        operationLog.warning(BackupComponentInt.COMPONENT.ZEEBE, "Nothing was changed: the platform is consistent, the cluster is back in PROCESSING mode.");
                    } else {
                        operationLog.error(BackupComponentInt.COMPONENT.ZEEBE, "Nothing was changed, and the data is consistent, but the cluster is still in recovery mode "
                                + "and could not be switched back: PATCH " + rest.getBaseUrl() + "/mode?mode=PROCESSING answered " + back.status() + " " + back.raw());
                    }
                }
            } else {
                // Not consistent: Optimize is not started, the cluster is not switched back. This is the last message of the log
                String where = zeebeRestoreStarted
                        ? "The secondary storage holds the data of the backup " + backupId + ", but the restore of Zeebe did not end: Zeebe holds a mix of its current data and of the backup."
                        : "The secondary storage was being replaced by the backup " + backupId + ": its indices are deleted or partly restored, and Zeebe still holds its current data.";
                operationLog.error(null, "THE PLATFORM IS NOT CONSISTENT. " + where
                        + " The cluster stays in recovery mode and Optimize stays stopped: do not start them. "
                        + "Fix the cause, then start the restore again (it deletes and restores again).");
            }
            throw e;
        }
    }

    private void waitUntil(String what, Condition condition) throws OperationException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isTrue()) {
                return;
            }
            sleep();
        }
        throw fail(504, "Timeout", "Waiting for " + what + ": not reached after " + timeoutSeconds + " seconds.");
    }

    private void sleep() throws OperationException {
        try {
            Thread.sleep(pollMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail(500, "Restore interrupted", "The restore was interrupted while it was waiting.");
        }
    }

    /**
     * The change is applied when the management API of Zeebe says that the last change is this one, and no change is pending.
     */
    private boolean changeApplied(long changeId) {
        if (changeId < 0) {
            return false;
        }
        ZeebeRestApi.RestResult changes = rest.getClusterChanges();
        if (!changes.isSuccess() || changes.body() == null) {
            return false;
        }
        JsonNode pending = changes.body().path("pendingChange");
        boolean noPending = pending.isMissingNode() || pending.isNull();
        return noPending && changes.body().path("lastChange").path("id").asLong(-2) == changeId;
    }

    public enum JOBSTATUS {PLANNED, INPROGRESS, TERMINATED, FAILED}

    @FunctionalInterface
    private interface Condition {
        boolean isTrue();
    }
}
