package io.camunda.blueberry.operation.restoration;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.ZeebeRestApi;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Restore of Camunda 8.10 with the Restore API: everything goes through the REST API of the cluster, there is no
 * Kubernetes, and the brokers are not restarted.
 * https://docs.camunda.io/docs/next/self-managed/operational-guides/backup-restore/elasticsearch/es-restore-api/
 * https://docs.camunda.io/docs/next/self-managed/operational-guides/backup-restore/rdbms/rdbms-restore-api/
 * <ol>
 *   <li>Validate: the Zeebe backup is complete, the secondary storage has the backup</li>
 *   <li>PATCH /v2/mode?mode=RECOVERING: the brokers stop processing</li>
 *   <li>Restore the secondary storage (the cluster stays in recovery mode):
 *     <ul><li>Elasticsearch: dry run of the restore of Zeebe, delete the indices, restore the snapshots one by one</li>
 *     <li>A database: restore with the native tool of the database</li></ul></li>
 *   <li>POST /v2/restore {"backupIds": [id]} (after a dry run for a database), wait with GET /v2/restore</li>
 *   <li>The cluster leaves the recovery mode itself: check GET /v2/topology</li>
 * </ol>
 * A failure before something is deleted puts the cluster back in PROCESSING. After that, the cluster stays in recovery mode:
 * the restore can be started again (the documentation: a partially restored cluster must not be started).
 * <p>
 * Optimize is not stopped by the REST API: with Elasticsearch, it must be stopped before the restore, and started after.
 */
@Component
public class RestoreApiExecutor {
    public static final String MODE_RECOVERING = "RECOVERING";
    public static final String MODE_PROCESSING = "PROCESSING";

    private static final CamundaApplicationInt.COMPONENT ZEEBE = CamundaApplicationInt.COMPONENT.ZEEBE;
    private static final CamundaApplicationInt.COMPONENT STORAGE = CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE;

    private final ZeebeRestApi rest;
    private final SecondaryStorageFactory secondaryStorageFactory;
    private final BooleanSupplier optimizeRunning;
    private final long pollMillis;
    private final int timeoutSeconds;

    @Autowired
    public RestoreApiExecutor(ZeebeRestApi rest, SecondaryStorageFactory secondaryStorageFactory,
                              BlueberryConfig blueberryConfig, OptimizeConnect optimizeConnect) {
        this(rest, secondaryStorageFactory,
                optimizeConnect::isReachable,
                2000, blueberryConfig.getRestoreZeebeTimeoutSeconds());
    }

    RestoreApiExecutor(ZeebeRestApi rest, SecondaryStorageFactory secondaryStorageFactory, BooleanSupplier optimizeRunning,
                       long pollMillis, int timeoutSeconds) {
        this.rest = rest;
        this.secondaryStorageFactory = secondaryStorageFactory;
        this.optimizeRunning = optimizeRunning;
        this.pollMillis = pollMillis;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Number of steps (operationStep) of the restore: the version profile gives the same list */
    public static final int NUMBER_OF_STEPS = 5;

    public static final String STEP_VALIDATE = "Validate";
    public static final String STEP_RECOVERY_MODE = "Recovery mode";
    public static final String STEP_SECONDARY_STORAGE = "Restore secondary storage";
    public static final String STEP_RESTORE_ZEEBE = "Restore Zeebe";
    public static final String STEP_WAIT_PROCESSING = "Wait for processing";
    /** The documentation of the restore */
    public static final String RESTORE_DOC = "https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/restore/";

    public void execute(long backupId, OperationLog log) throws OperationException {
        // 1. Validate
        log.startOperationStep(ZEEBE, STEP_VALIDATE, "Validate the backup and the cluster", RESTORE_DOC);
        SecondaryStorageInt secondaryStorage = validate(backupId, log);

        // 2. Recovery mode
        log.startOperationStep(ZEEBE, STEP_RECOVERY_MODE, "Enter the recovery mode", RESTORE_DOC);
        enterRecoveryMode(log);

        boolean somethingDeleted = false;
        boolean zeebeRestoreStarted = false;
        try {
            // 3. The secondary storage
            log.startOperationStep(STORAGE, STEP_SECONDARY_STORAGE, "Restore the secondary storage [" + secondaryStorage.getName() + "]", RESTORE_DOC);
            if (!secondaryStorage.isDatabase()) {
                // Elasticsearch: nothing is deleted before the restore of Zeebe is known to be possible
                dryRun(backupId, log);
            }
            somethingDeleted = true;
            secondaryStorage.restore(backupId, log);

            // 4. Zeebe
            log.startOperationStep(ZEEBE, STEP_RESTORE_ZEEBE, "Restore Zeebe", RESTORE_DOC);
            if (secondaryStorage.isDatabase()) {
                // The restore of Zeebe reads the exporter position in the restored database
                dryRun(backupId, log);
            }
            zeebeRestoreStarted = true;
            restoreZeebe(backupId, log);

            // 5. The cluster is back
            log.startOperationStep(ZEEBE, STEP_WAIT_PROCESSING, "Wait for the end of the recovery mode", RESTORE_DOC);
            waitProcessing(log);
        } catch (OperationException | RuntimeException e) {
            handleFailure(somethingDeleted, zeebeRestoreStarted, log);
            throw e;
        }

        log.info(null, "The restore is done and must be validated manually: open Operate and Tasklist, check the data from the backup time, "
                + "and start one test process. If Optimize was stopped for the restore, start it now.");
    }

    /* ******************************************************************** */
    /*  1. Validate                                                         */
    /* ******************************************************************** */

    private SecondaryStorageInt validate(long backupId, OperationLog log) throws OperationException {
        ZeebeRestApi.RestResult topology = rest.getTopology();
        if (!topology.isSuccess()) {
            throw fail(503, "The REST API of the cluster does not answer",
                    "GET " + rest.getBaseUrl() + "/topology answered " + topology.status() + " " + topology.raw()
                            + ". The Restore API exists from Camunda 8.10 (/v2/mode, /v2/restore). "
                            + "Check blueberry.zeebeRestUrl (or camunda.client.zeebe.restAddress) and the credentials.");
        }
        log.info(ZEEBE, "The REST API answers: " + rest.getBaseUrl());

        ZeebeRestApi.RestResult backup = rest.getRuntimeBackup(backupId);
        if (!backup.isSuccess() || backup.body() == null) {
            throw fail(404, "Zeebe backup not found", "GET " + rest.getBaseUrl() + "/backups/runtime/" + backupId
                    + " answered " + backup.status() + " " + backup.raw());
        }
        String state = backup.body().path("state").asText();
        if (!"COMPLETED".equalsIgnoreCase(state)) {
            throw fail(400, "Zeebe backup not complete", "The Zeebe backup " + backupId + " is " + state + ", not COMPLETED."
                    + (backup.body().path("failureReason").isTextual() ? " " + backup.body().path("failureReason").asText() : ""));
        }
        log.info(ZEEBE, "The Zeebe backup " + backupId + " is COMPLETED");

        SecondaryStorageInt secondaryStorage = secondaryStorageFactory.getSecondaryStorage();
        if (secondaryStorage == null) {
            throw fail(400, "Secondary storage unknown", "The secondary storage is not detected on the cluster, and blueberry.secondaryStorage is not set "
                    + "(or has no implementation). Supported: " + String.join(", ", secondaryStorageFactory.getSupportedNames()));
        }
        if (!secondaryStorage.supportsRestore()) {
            throw fail(400, "Restore not available", "Blueberry can't restore the secondary storage [" + secondaryStorage.getName() + "].");
        }
        secondaryStorage.checkRestorable(backupId, log);
        log.info(STORAGE, "The backup " + backupId + " exists in the secondary storage [" + secondaryStorage.getName() + "]");

        // The documentation: with Elasticsearch, Optimize is the only component to stop. The REST API can't stop it.
        if (!secondaryStorage.isDatabase() && optimizeRunning.getAsBoolean()) {
            throw fail(409, "Stop Optimize before the restore",
                    "Optimize is running. With Elasticsearch, Optimize must be stopped during the restore (its indices are deleted and restored), "
                            + "and the REST API can't stop it. Stop it (for example: kubectl scale deployment camunda-optimize --replicas=0, or optimize.enabled: false "
                            + "in the Helm values), then start the restore again. If Optimize is not deployed, set blueberry.optimize.enabled=false.");
        }
        return secondaryStorage;
    }

    /* ******************************************************************** */
    /*  2. Recovery mode                                                    */
    /* ******************************************************************** */

    private void enterRecoveryMode(OperationLog log) throws OperationException {
        ZeebeRestApi.RestResult mode = rest.changeMode(MODE_RECOVERING);
        if (!mode.isSuccess()) {
            throw fail(mode.status(), "Can't enter the recovery mode",
                    "PATCH " + rest.getBaseUrl() + "/mode?mode=RECOVERING answered " + mode.status() + " " + mode.raw());
        }
        long changeId = changeId(mode);
        log.info(ZEEBE, "Recovery mode requested, change " + changeId);

        // A restore sent before every broker is in recovery mode is refused (409): wait for it
        waitUntil("the recovery mode of all the partitions", () -> {
            ZeebeRestApi.RestResult topology = rest.getTopology();
            return (topology.isSuccess() && allPartitionsRecovering(topology.body())) || changeApplied(changeId);
        });
        log.info(ZEEBE, "The cluster is in recovery mode");
    }

    /* ******************************************************************** */
    /*  3 and 4. Restore                                                    */
    /* ******************************************************************** */

    private void dryRun(long backupId, OperationLog log) throws OperationException {
        ZeebeRestApi.RestResult dryRun = rest.restore(List.of(backupId), true);
        if (!dryRun.isSuccess()) {
            throw fail(dryRun.status() == 0 ? 500 : dryRun.status(), "The restore of Zeebe is refused",
                    "POST " + rest.getBaseUrl() + "/restore?dryRun=true answered " + dryRun.status() + " " + dryRun.raw()
                            + ". Nothing is restored.");
        }
        log.info(ZEEBE, "Dry run of the restore of Zeebe: accepted (" + dryRun.status() + ")");
    }

    private void restoreZeebe(long backupId, OperationLog log) throws OperationException {
        ZeebeRestApi.RestResult restore = rest.restore(List.of(backupId), false);
        if (!restore.isSuccess()) {
            throw fail(restore.status() == 0 ? 500 : restore.status(), "The restore of Zeebe is refused",
                    "POST " + rest.getBaseUrl() + "/restore answered " + restore.status() + " " + restore.raw());
        }
        long changeId = changeId(restore);
        log.info(ZEEBE, "Restore of Zeebe started, change " + changeId);

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        String lastStatus = "";
        while (System.currentTimeMillis() < deadline) {
            ZeebeRestApi.RestResult status = rest.getRestoreStatus();
            if (status.status() == 404) {
                // The API gives no status when the restore is finished
                log.info(ZEEBE, "Restore of Zeebe finished (GET /restore: 404)" + (changeApplied(changeId) ? ", change " + changeId + " applied" : ""));
                return;
            }
            if (status.isSuccess() && status.body() != null) {
                String state = text(status.body(), "status", "state");
                if (!state.equals(lastStatus)) {
                    log.info(ZEEBE, "Restore of Zeebe: " + state);
                    lastStatus = state;
                }
                if ("COMPLETED".equalsIgnoreCase(state)) {
                    return;
                }
                if ("FAILED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                    throw fail(500, "The restore of Zeebe is " + state, "GET " + rest.getBaseUrl() + "/restore: " + status.raw()
                            + ". Fix the cause (the partitions try again by themselves). If the backup is wrong: DELETE "
                            + "{management}/actuator/cluster/changes/" + changeId + ", then start the restore again. "
                            + "Do not switch the cluster to PROCESSING after a partial restore.");
                }
            }
            sleep();
        }
        throw fail(504, "The restore of Zeebe is too long", "The restore is not finished after " + timeoutSeconds
                + " seconds (blueberry.restore.zeebeRestoreTimeoutSeconds). Last status: " + lastStatus);
    }

    /* ******************************************************************** */
    /*  5. Back to processing                                               */
    /* ******************************************************************** */

    private void waitProcessing(OperationLog log) throws OperationException {
        waitUntil("the end of the recovery mode", () -> {
            ZeebeRestApi.RestResult topology = rest.getTopology();
            return topology.isSuccess() && topology.body() != null && !anyPartitionRecovering(topology.body());
        });
        log.info(ZEEBE, "The cluster left the recovery mode: " + rest.getBaseUrl() + "/topology");
    }

    /* ******************************************************************** */
    /*  Failure                                                             */
    /* ******************************************************************** */

    /**
     * Before anything is deleted, the cluster goes back to PROCESSING. After, it stays in recovery mode: the restore must be
     * started again.
     */
    private void handleFailure(boolean somethingDeleted, boolean zeebeRestoreStarted, OperationLog log) {
        if (zeebeRestoreStarted) {
            log.error(ZEEBE, "The restore of Zeebe has started: the cluster stays in recovery mode. Fix the cause, then start the restore again "
                    + "(or follow the documentation to cancel the change).");
        } else if (somethingDeleted) {
            log.error(STORAGE, "The restore of the secondary storage did not end: the cluster stays in recovery mode. "
                    + "Fix the cause, then start the restore again (it deletes and restores again).");
        } else {
            ZeebeRestApi.RestResult back = rest.changeMode(MODE_PROCESSING);
            if (back.isSuccess()) {
                log.warning(ZEEBE, "Nothing was changed: the cluster is back in PROCESSING mode.");
            } else {
                log.error(ZEEBE, "Nothing was changed, but the cluster is still in recovery mode and could not be switched back: PATCH "
                        + rest.getBaseUrl() + "/mode?mode=PROCESSING answered " + back.status() + " " + back.raw());
            }
        }
    }

    /* ******************************************************************** */
    /*  Tools                                                               */
    /* ******************************************************************** */

    @FunctionalInterface
    private interface Condition {
        boolean isTrue();
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
        return !partitions.isEmpty() && partitions.stream().allMatch(RestoreApiExecutor::isRecovering);
    }

    static boolean anyPartitionRecovering(JsonNode topology) {
        return partitions(topology).stream().anyMatch(RestoreApiExecutor::isRecovering);
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

    private static OperationException fail(int status, String title, String message) {
        return new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, status, title, message);
    }
}
