package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.connect.BackupComponentInt;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.ZeebeRestApi;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.TestOrchestration;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restore with the Restore API: the order of the calls, and what happens when a step fails.
 */
class RestoreJobTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RECOVERING = "{\"brokers\":[{\"partitions\":[{\"partitionId\":1,\"role\":\"leader\",\"health\":\"RECOVERING\"}]},"
            + "{\"brokers\":0,\"partitions\":[{\"partitionId\":2,\"state\":\"recovering\"}]}]}";
    private static final String PROCESSING = "{\"brokers\":[{\"partitions\":[{\"partitionId\":1,\"health\":\"HEALTHY\"}]}]}";

    /**
     * The REST API of the cluster: it records each call and answers from a script
     */
    private static class FakeRest extends ZeebeRestApi {
        final List<String> calls = new ArrayList<>();
        String backupState = "COMPLETED";
        int dryRunStatus = 202;
        String restoreStatusBody = "{\"status\":\"COMPLETED\"}";
        int restoreStatusHttp = 200;
        boolean restoreStarted = false;

        FakeRest() {
            super(config(), null);
        }

        private static BlueberryConfig config() {
            BlueberryConfig config = new BlueberryConfig();
            set(config, "zeebeRestUrl", "http://zeebe:8080");
            return config;
        }

        @Override
        public RestResult call(HttpMethod method, String path, Object body) {
            String call = method + " " + path;
            calls.add(call);
            switch (call) {
                case "GET /topology":
                    return result(200, restoreStarted ? PROCESSING : (calls.contains("PATCH /mode?mode=RECOVERING") ? RECOVERING : PROCESSING));
                case "GET /backups/runtime/7":
                    return result(200, "{\"backupId\":7,\"state\":\"" + backupState + "\"}");
                case "PATCH /mode?mode=RECOVERING":
                    return result(202, "{\"changeId\":5}");
                case "PATCH /mode?mode=PROCESSING":
                    return result(202, "{\"changeId\":9}");
                case "POST /restore?dryRun=true":
                    return result(dryRunStatus, dryRunStatus < 300 ? "{\"changeId\":6}" : "{\"title\":\"refused\"}");
                case "POST /restore":
                    restoreStarted = true;
                    return result(202, "{\"changeId\":6}");
                case "GET /restore":
                    return result(restoreStatusHttp, restoreStatusBody);
                default:
                    return result(404, "{}");
            }
        }

        @Override
        public RestResult callUrl(HttpMethod method, String url, Object body) {
            // the management API: not used by these scripts
            return result(404, "{}");
        }

        boolean called(String call) {
            return calls.contains(call);
        }

        int position(String call) {
            return calls.indexOf(call);
        }

        private static RestResult result(int status, String json) {
            try {
                return new RestResult(status, json, MAPPER.readTree(json));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * A secondary storage which records its calls in the same list as the REST API
     */
    private static class FakeStorage implements SecondaryStorageInt {
        final List<String> calls;
        final boolean database;
        boolean failRestore = false;

        FakeStorage(List<String> calls, boolean database) {
            this.calls = calls;
            this.database = database;
        }

        public String getName() {
            return "Fake";
        }

        public boolean isDatabase() {
            return database;
        }

        public boolean supportsRestore() {
            return true;
        }

        public void checkRestorable(long backupId, OperationLog operationLog) {
            calls.add("STORAGE check");
        }

        public void restore(long backupId, OperationLog operationLog) throws OperationException {
            calls.add("STORAGE restore");
            if (failRestore) {
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 500, "restore", "the storage failed");
            }
        }

        public boolean isActive() {
            return true;
        }

        public BackupComponentInt.COMPONENT getComponent() {
            return BackupComponentInt.COMPONENT.SECONDARYSTORAGE;
        }

        public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
            return new BackupOperation();
        }

        public BackupOperation waitBackup(Long backupId, OperationLog operationLog) throws BackupException {
            return new BackupOperation();
        }

        public List<BackupInfo> getListBackups() {
            return List.of();
        }

        public String getUrlListBackup() {
            return "";
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Optimize in the cluster: the fake records its stop and its start in the calls of the rest fake */
    private static RestoreJob executor(FakeRest rest, FakeStorage storage, BooleanSupplier optimizeInCluster) {
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(storage), TestOrchestration.build(Map.of(OrchestrationParameter.SECONDARY_STORAGE.name(), "fake"), null));
        OptimizeConnect optimize = new OptimizeConnect(new BlueberryConfig(), new RestTemplate(), null) {
            @Override
            public boolean isActive() {
                return optimizeInCluster.getAsBoolean();
            }

            @Override
            public int stop(OperationLog log) {
                rest.calls.add("OPTIMIZE stop");
                return 1;
            }

            @Override
            public void start(int replicas, OperationLog log) {
                rest.calls.add("OPTIMIZE start " + replicas);
            }
        };
        return new RestoreJob(optimize, rest, factory, new OperationLog(), 1, 5);
    }

    @Test
    void elasticsearchIsRestoredAfterADryRunOfTheRestoreOfZeebe() throws Exception {
        FakeRest rest = new FakeRest();
        FakeStorage storage = new FakeStorage(rest.calls, false);

        executor(rest, storage, () -> false).restore(7);

        // the order of the documentation, and nothing is deleted before the dry run is accepted
        assertTrue(rest.position("GET /backups/runtime/7") < rest.position("PATCH /mode?mode=RECOVERING"));
        assertTrue(rest.position("PATCH /mode?mode=RECOVERING") < rest.position("POST /restore?dryRun=true"));
        assertTrue(rest.position("POST /restore?dryRun=true") < rest.position("STORAGE restore"));
        assertTrue(rest.position("STORAGE restore") < rest.position("POST /restore"));
        assertTrue(rest.position("POST /restore") < rest.position("GET /restore"));
        // the cluster leaves the recovery mode by itself: it is never switched
        assertFalse(rest.called("PATCH /mode?mode=PROCESSING"));
    }

    @Test
    void aDatabaseIsRestoredBeforeTheDryRun() throws Exception {
        FakeRest rest = new FakeRest();
        FakeStorage storage = new FakeStorage(rest.calls, true);

        executor(rest, storage, () -> false).restore(7);

        // Zeebe reads the exporter position in the restored database
        assertTrue(rest.position("STORAGE restore") < rest.position("POST /restore?dryRun=true"));
        assertTrue(rest.position("POST /restore?dryRun=true") < rest.position("POST /restore"));
    }

    @Test
    void optimizeIsStoppedAfterTheValidationAndStartedAtTheEnd() throws Exception {
        FakeRest rest = new FakeRest();
        FakeStorage storage = new FakeStorage(rest.calls, false);

        executor(rest, storage, () -> true).restore(7);

        assertTrue(rest.position("GET /backups/runtime/7") < rest.position("OPTIMIZE stop"));
        assertTrue(rest.position("OPTIMIZE stop") < rest.position("PATCH /mode?mode=RECOVERING"));
        assertTrue(rest.position("STORAGE restore") < rest.position("OPTIMIZE start 1"));
        assertTrue(rest.position("GET /restore") < rest.position("OPTIMIZE start 1"));
    }

    @Test
    void optimizeNotInTheClusterIsNotTouched() throws Exception {
        FakeRest rest = new FakeRest();
        FakeStorage storage = new FakeStorage(rest.calls, false);

        executor(rest, storage, () -> false).restore(7);

        assertFalse(rest.called("OPTIMIZE stop"));
        assertFalse(rest.called("OPTIMIZE start 1"));
        assertTrue(rest.called("POST /restore"));
    }

    @Test
    void optimizeIsNotStoppedWhenTheValidationFails() {
        FakeRest rest = new FakeRest();
        rest.backupState = "IN_PROGRESS";
        FakeStorage storage = new FakeStorage(rest.calls, false);

        assertThrows(OperationException.class, () -> executor(rest, storage, () -> true).restore(7));

        // the validation is the first step: nothing is stopped, nothing to start again
        assertFalse(rest.called("OPTIMIZE stop"));
        assertFalse(rest.called("OPTIMIZE start 1"));
        assertFalse(rest.called("PATCH /mode?mode=RECOVERING"));
    }

    @Test
    void aBackupWhichIsNotCompletedIsRefused() {
        FakeRest rest = new FakeRest();
        rest.backupState = "IN_PROGRESS";
        FakeStorage storage = new FakeStorage(rest.calls, false);

        assertThrows(OperationException.class, () -> executor(rest, storage, () -> false).restore(7));

        assertFalse(rest.called("PATCH /mode?mode=RECOVERING"));
    }

    @Test
    void whenTheDryRunIsRefusedNothingIsDeletedAndTheClusterIsBackToProcessing() {
        FakeRest rest = new FakeRest();
        rest.dryRunStatus = 404;
        FakeStorage storage = new FakeStorage(rest.calls, false);

        OperationException e = assertThrows(OperationException.class, () -> executor(rest, storage, () -> false).restore(7));

        assertEquals(404, e.getStatus());
        assertFalse(rest.called("STORAGE restore"));
        assertFalse(rest.called("POST /restore"));
        assertTrue(rest.called("PATCH /mode?mode=PROCESSING"), "nothing was changed: the cluster must process again");
    }

    @Test
    void whenTheStorageFailsTheClusterStaysInRecoveryMode() {
        FakeRest rest = new FakeRest();
        FakeStorage storage = new FakeStorage(rest.calls, false);
        storage.failRestore = true;

        assertThrows(OperationException.class, () -> executor(rest, storage, () -> false).restore(7));

        // some indices may be deleted: the restore must be started again, the cluster must not process
        assertFalse(rest.called("PATCH /mode?mode=PROCESSING"));
        assertFalse(rest.called("POST /restore"));
    }

    @Test
    void whenTheRestoreOfZeebeFailsTheClusterStaysInRecoveryMode() {
        FakeRest rest = new FakeRest();
        rest.restoreStatusBody = "{\"status\":\"FAILED\"}";
        FakeStorage storage = new FakeStorage(rest.calls, false);

        OperationException e = assertThrows(OperationException.class, () -> executor(rest, storage, () -> false).restore(7));

        assertTrue(e.getMessage().contains("FAILED"), e.getMessage());
        assertFalse(rest.called("PATCH /mode?mode=PROCESSING"));
    }

    @Test
    void theRestoreIsFinishedWhenTheApiAnswersNotFound() throws Exception {
        FakeRest rest = new FakeRest();
        rest.restoreStatusHttp = 404;
        FakeStorage storage = new FakeStorage(rest.calls, false);

        executor(rest, storage, () -> false).restore(7);

        assertTrue(rest.called("GET /restore"));
    }

    private static String lastMessage(RestoreJob job) {
        List<OperationLog.Message> messages = job.getOperationLog().getMessages();
        List<String> content = messages.get(messages.size() - 1).content;
        return content.get(content.size() - 1);
    }

    @Test
    void aFailureWhileTheStorageIsReplacedLeavesThePlatformInconsistentAndOptimizeStopped() {
        FakeRest rest = new FakeRest();
        FakeStorage storage = new FakeStorage(rest.calls, false);
        storage.failRestore = true;
        RestoreJob job = executor(rest, storage, () -> true);

        assertThrows(OperationException.class, () -> job.restore(7));

        assertFalse(job.isConsistent());
        assertFalse(rest.called("OPTIMIZE start 1"), "Optimize must not start on a platform which is not consistent");
        assertTrue(lastMessage(job).contains("NOT CONSISTENT"), lastMessage(job));
    }

    @Test
    void aFailureOfTheRestoreOfZeebeLeavesThePlatformInconsistent() {
        FakeRest rest = new FakeRest();
        rest.restoreStatusBody = "{\"status\":\"FAILED\"}";
        FakeStorage storage = new FakeStorage(rest.calls, false);
        RestoreJob job = executor(rest, storage, () -> true);

        assertThrows(OperationException.class, () -> job.restore(7));

        assertFalse(job.isConsistent());
        assertFalse(rest.called("OPTIMIZE start 1"));
        assertTrue(lastMessage(job).contains("NOT CONSISTENT"), lastMessage(job));
    }

    @Test
    void aFailureBeforeTheDataIsReplacedLeavesThePlatformConsistent() {
        FakeRest rest = new FakeRest();
        rest.dryRunStatus = 404;
        FakeStorage storage = new FakeStorage(rest.calls, false);
        RestoreJob job = executor(rest, storage, () -> true);

        assertThrows(OperationException.class, () -> job.restore(7));

        assertTrue(job.isConsistent());
        assertTrue(rest.called("OPTIMIZE start 1"), "nothing was changed: Optimize is started again");
        assertTrue(rest.called("PATCH /mode?mode=PROCESSING"));
        assertFalse(lastMessage(job).contains("NOT CONSISTENT"), lastMessage(job));
    }

    @Test
    void aSuccessfulRestoreIsConsistent() throws Exception {
        FakeRest rest = new FakeRest();
        FakeStorage storage = new FakeStorage(rest.calls, false);
        RestoreJob job = executor(rest, storage, () -> true);

        job.restore(7);

        assertTrue(job.isConsistent());
        assertEquals(RestoreJob.JOBSTATUS.TERMINATED, job.getJobStatus());
    }

    @Test
    void theRecoveryModeIsReadInTheTopology() throws Exception {
        assertTrue(RestoreJob.allPartitionsRecovering(MAPPER.readTree(RECOVERING)));
        assertFalse(RestoreJob.allPartitionsRecovering(MAPPER.readTree(PROCESSING)));
        assertFalse(RestoreJob.allPartitionsRecovering(MAPPER.readTree("{\"brokers\":[]}")));
        assertTrue(RestoreJob.anyPartitionRecovering(MAPPER.readTree(RECOVERING)));
        assertFalse(RestoreJob.anyPartitionRecovering(MAPPER.readTree(PROCESSING)));
    }

    @Test
    void theChangeIdIsInTheAnswer() throws Exception {
        assertEquals(5L, RestoreJob.changeId(new ZeebeRestApi.RestResult(202, "{\"changeId\":5}", MAPPER.readTree("{\"changeId\":5}"))));
        assertEquals(-1L, RestoreJob.changeId(new ZeebeRestApi.RestResult(204, "", null)));
    }
}

