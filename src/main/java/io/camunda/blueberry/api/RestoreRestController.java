package io.camunda.blueberry.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.operation.restoration.RestorationJob;
import io.camunda.blueberry.operation.restoration.RestorationManager;
import io.camunda.blueberry.operation.restoration.RestoreEnvironmentCheck;
import io.camunda.blueberry.operation.restoration.RestorePreflight;
import io.camunda.blueberry.platform.version.RestorePhase;
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

    private final RestorationManager restorationManager;
    private final RestoreEnvironmentCheck restoreEnvironmentCheck;
    Logger logger = LoggerFactory.getLogger(RestoreRestController.class);

    public RestoreRestController(RestorationManager restorationManager,
                                 RestoreEnvironmentCheck restoreEnvironmentCheck) {
        this.restorationManager = restorationManager;
        this.restoreEnvironmentCheck = restoreEnvironmentCheck;
    }

    @GetMapping(value = "/api/restore/readiness", produces = "application/json")
    public Map<String, Object> readiness() {
        return restoreEnvironmentCheck.readiness();
    }

    @GetMapping(value = "/api/restore/phases", produces = "application/json")
    public Map<String, Object> phases() {
        List<Map<String, Object>> phaseMaps = restorationManager.phasesForActiveVersion().stream()
                .map(this::phaseToMap)
                .collect(Collectors.toList());
        boolean pwc87 = phaseMaps.stream().anyMatch(phase -> "zeebe_delete_volumes".equals(phase.get("id")));
        Map<String, Object> result = new HashMap<>();
        result.put("phases", phaseMaps);
        result.put("executionEnabled", restorationManager.isLiveRestoreEnabled());
        result.put("procedure", pwc87 ? "pwc-8.7" : "manual");
        return result;
    }

    @GetMapping(value = "/api/restore/preflight", produces = "application/json")
    public Map<String, Object> preflight(@RequestParam(name = "backupId") long backupId) {
        try {
            return preflightToMap(restorationManager.preflight(backupId));
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
            restorationManager.startRestoration(backupId);
            RestorationJob job = restorationManager.getCurrentRestorationJob();
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
        RestorationJob job = restorationManager.getCurrentRestorationJob();
        if (job != null) {
            monitorStatus.statusRestore = job.getJobStatus().toString();
            monitorStatus.backupId = job.getBackupId();
            monitorStatus.camundaMinorVersion = job.getProfile().minorVersion();
            monitorStatus.docsRestoreUrl = job.getProfile().docsRestoreUrl();
            OperationLog operationLog = job.getOperationLog();
            if (operationLog != null) {
                monitorStatus.step = operationLog.getCurrentStep();
                monitorStatus.totalNumberOfSteps = operationLog.getTotalNumberOfSteps();
                monitorStatus.operationName = operationLog.getOperationName();
                monitorStatus.stepName = operationLog.getStepName();
                monitorStatus.messages = operationLog.getMessages();
            }
        }
        monitorStatus.dateStatus = DateOperation.dateTimeToHumanString(DateOperation.getLocalDateTimeNow(), timezoneOffset);
        return monitorStatus;
    }

    private Map<String, Object> phaseToMap(RestorePhase phase) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", phase.id());
        map.put("title", phase.title());
        map.put("kind", phase.kind().name());
        if (phase.manualHint() != null) {
            map.put("manualHint", phase.manualHint());
        }
        return map;
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
        map.put("camundaMinorVersion", result.camundaMinorVersion());
        map.put("docsRestoreUrl", result.docsRestoreUrl());
        return map;
    }

    private static class MonitorStatus {
        @JsonProperty
        String statusRestore;
        @JsonProperty
        int step;
        @JsonProperty
        int totalNumberOfSteps;
        @JsonProperty
        String operationName;
        @JsonProperty
        String stepName;
        @JsonProperty
        String dateStatus;
        @JsonProperty
        Long backupId;
        @JsonProperty
        String camundaMinorVersion;
        @JsonProperty
        String docsRestoreUrl;
        @JsonProperty
        List<OperationLog.Message> messages;
    }
}
