package io.camunda.blueberry.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.operation.restoration.RestoreEnvironmentCheck;
import io.camunda.blueberry.operation.restoration.RestoreJob;
import io.camunda.blueberry.operation.restoration.RestoreManager;
import io.camunda.blueberry.operation.restoration.RestorePreflight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("blueberry")
public class RestoreRestController {

    private final RestoreManager restoreManager;
    private final RestoreEnvironmentCheck restoreEnvironmentCheck;
    Logger logger = LoggerFactory.getLogger(RestoreRestController.class);

    public RestoreRestController(RestoreManager restoreManager,
                                 RestoreEnvironmentCheck restoreEnvironmentCheck) {
        this.restoreManager = restoreManager;
        this.restoreEnvironmentCheck = restoreEnvironmentCheck;
    }

    @GetMapping(value = "/api/restore/readiness", produces = "application/json")
    public Map<String, Object> readiness() {
        return restoreEnvironmentCheck.readiness();
    }

    /**
     * The steps of the restore, in the order of the job: the list of RestoreJob.STEPS. The Restore page displays it.
     */
    @GetMapping(value = "/api/restore/steps", produces = "application/json")
    public Map<String, Object> steps() {
        List<Map<String, Object>> stepMaps = new java.util.ArrayList<>();
        for (int i = 0; i < RestoreJob.STEPS.size(); i++) {
            stepMaps.add(Map.of("number", i + 1, "name", RestoreJob.STEPS.get(i)));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("steps", stepMaps);
        result.put("docsRestoreUrl", RestoreJob.RESTORE_DOC);
        return result;
    }

    /**
     * Does the configuration allow the live restore (blueberry.restore.execution)?
     */
    @GetMapping(value = "/api/restore/execution", produces = "application/json")
    public Map<String, Object> execution() {
        Map<String, Object> result = new HashMap<>();
        result.put("executionEnabled", restoreManager.isLiveRestoreEnabled());
        // Why the live restore can be off: the configuration (blueberry.restore.execution)
        result.put("executionConfigured", restoreManager.isExecutionConfigured());
        return result;
    }

    @GetMapping(value = "/api/restore/preflight", produces = "application/json")
    public Map<String, Object> preflight(@RequestParam(name = "backupId") long backupId) {
        try {
            return preflightToMap(restoreManager.preflight(backupId));
        } catch (OperationException e) {
            return e.getRecord();
        }
    }

    @PostMapping(value = "/api/restore/start", produces = "application/json")
    public Map<String, Object> start(@RequestBody Map<String, Long> body) {
        Long backupId = body.get("backupId");
        if (backupId == null) {
            return Map.of("error", "missing_backup_id", "errorMessage", "backupId is required");
        }
        try {
            logger.info("Rest [/api/restore/start] backupId={}", backupId);
            restoreManager.startRestoration(backupId);
            RestoreJob job = restoreManager.getCurrentRestorationJob();
            Map<String, Object> result = new HashMap<>();
            result.put("statusOperation", job == null ? "STARTED" : job.getJobStatus().toString());
            result.put("backupId", backupId);
            return result;
        } catch (OperationException e) {
            return e.getRecord();
        }
    }

    @GetMapping(value = "/api/restore/monitor", produces = "application/json")
    public MonitorStatus monitor(@RequestParam(name = "timezoneoffset") Long timezoneOffset) {
        MonitorStatus monitorStatus = new MonitorStatus();
        monitorStatus.statusRestore = "READY";
        RestoreJob job = restoreManager.getCurrentRestorationJob();
        if (job != null) {
            monitorStatus.statusRestore = job.getJobStatus().toString();
            monitorStatus.backupId = job.getBackupId();
            monitorStatus.docsRestoreUrl = RestoreJob.RESTORE_DOC;
            monitorStatus.consistent = job.isConsistent();
            OperationLog operationLog = job.getOperationLog();
            if (operationLog != null) {
                monitorStatus.step = operationLog.getCurrentStep();
                monitorStatus.totalSteps = operationLog.getTotalNumberOfSteps();
                monitorStatus.operationName = operationLog.getOperationName();
                monitorStatus.stepName = operationLog.getStepName();
                monitorStatus.messages = operationLog.getMessages();
            }
        }
        monitorStatus.dateStatus = DateOperation.dateTimeToHumanString(DateOperation.getLocalDateTimeNow(), timezoneOffset);
        return monitorStatus;
    }

    private Map<String, Object> preflightToMap(RestorePreflight.Result result) {
        Map<String, Object> map = new HashMap<>();
        map.put("allowed", result.allowed());
        map.put("backupId", result.backupId());
        map.put("complete", result.complete());
        map.put("presentComponents", result.presentComponents());
        map.put("missingComponents", result.missingComponents());
        map.put("backupStatus", result.backupStatus());
        map.put("message", result.message());
        map.put("docsRestoreUrl", result.docsRestoreUrl());
        return map;
    }

    private static class MonitorStatus {
        @JsonProperty
        String statusRestore;
        @JsonProperty
        int step;
        @JsonProperty
        int totalSteps;
        @JsonProperty
        String operationName;
        @JsonProperty
        String stepName;
        @JsonProperty
        String dateStatus;
        @JsonProperty
        Long backupId;
        @JsonProperty
        boolean consistent = true;
        @JsonProperty
        String docsRestoreUrl;
        @JsonProperty
        List<OperationLog.Message> messages;
    }
}
