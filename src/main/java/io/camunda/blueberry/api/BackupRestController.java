package io.camunda.blueberry.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.ZeebeConnect;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.operation.backup.BackupCompleteness;
import io.camunda.blueberry.operation.backup.BackupJob;
import io.camunda.blueberry.operation.backup.BackupManager;
import io.camunda.blueberry.platform.version.CamundaVersionService;
import io.camunda.blueberry.platform.version.RestorePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("blueberry")


public class BackupRestController {

    private final ZeebeConnect zeebeConnect;
    private final BackupManager backupManager;
    private final CamundaVersionService versionService;
    Logger logger = LoggerFactory.getLogger(BackupRestController.class);

    public BackupRestController(ZeebeConnect zeebeConnect, BackupManager backupManager,
                                CamundaVersionService versionService) {
        this.zeebeConnect = zeebeConnect;
        this.backupManager = backupManager;
        this.versionService = versionService;
    }

    @GetMapping(value = "/api/backup/phases", produces = "application/json")
    public Map<String, Object> phases() {
        List<Map<String, Object>> phaseMaps = versionService.getActiveProfile().backupPhases().stream()
                .map(this::phaseToMap)
                .collect(Collectors.toList());
        Map<String, Object> result = new HashMap<>();
        result.put("phases", phaseMaps);
        result.put("docsBackupUrl", versionService.getActiveProfile().docsBackupUrl());
        result.put("camundaMinorVersion", versionService.getActiveProfile().minorVersion());
        return result;
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


    @PostMapping(value = "/api/backup/start", produces = "application/json")
    public Map<String, Object> startBackup(@RequestBody BackupManager.BackupParameter backupParameter) {
        // Return the backupId if no one was given

        logger.info("Rest [/api/backup/start] nextId[{}] backupId[{}]", backupParameter.nextId, backupParameter.backupId);
        try {
            backupManager.startBackup(backupParameter);
        } catch (OperationException e) {
            return e.getRecord();
        }
        // The backup is started asynchronously
        BackupJob backupJob = backupManager.getBackupJob();
        Map<String, Object> status = new HashMap<>();
        status.put("statusOperation", backupJob == null ? "STARTED" : backupJob.getJobStatus().toString());
        status.put("backupId", backupJob == null ? -1 : backupJob.getBackupId());

        return status;
    }

    @GetMapping(value = "/api/backup/monitor", produces = "application/json")
    public MonitorStatus monitorBackup(@RequestParam(name = "timezoneoffset") Long timezoneOffset) {

        MonitorStatus monitorStatus = new MonitorStatus();
        monitorStatus.statusBackup = "READY";
        BackupJob backupJob = backupManager.getBackupJob();

        if (backupJob != null) {
            monitorStatus.statusBackup = backupJob.getJobStatus().toString();
            OperationLog operationLog = backupJob.getOperationLog();
            if (operationLog != null) {
                monitorStatus.step = operationLog.getCurrentStep();
                monitorStatus.totalNumberOfSteps = operationLog.getTotalNumberOfSteps();
                monitorStatus.operationName = operationLog.getOperationName();
                monitorStatus.stepName = operationLog.getStepName();

                // get the last message
                monitorStatus.messages = operationLog.getMessages();

            }
            monitorStatus.backupId = backupJob.getBackupId();
        }
        monitorStatus.dateStatus = DateOperation.dateTimeToHumanString(DateOperation.getLocalDateTimeNow(), timezoneOffset);
        return monitorStatus;
    }


    /**
     * Return the list of existing Backup
     *
     * @return
     */
    @GetMapping(value = "/api/backup/list", produces = "application/json")
    public Map<String, Object> listBackups(@RequestParam(name = "timezoneoffset") Long timezoneOffset) {

        Map<String, Object> result = new HashMap<>();
        try {
            logger.debug("Rest [/api/backup/list]");

            BackupManager.ListBackupResult listBackupResult = backupManager.getListBackups();
            List<BackupCompleteness.Detail> completenessDetails = backupManager.getBackupCompletenessDetails();
            Map<Long, BackupCompleteness.Detail> completenessById = completenessDetails.stream()
                    .collect(Collectors.toMap(d -> d.backupId(), d -> d, (a, b) -> a));

            logger.info("Rest [/api/backup/list] found {} backups", listBackupResult.listBackups.size());
            BackupJob backupJob = backupManager.getBackupJob();

            List<Map<String, Object>> listBackupMap = listBackupResult.listBackups.stream().map(obj -> {
                        Map<String, Object> mapRecord = toBackupMap(obj, timezoneOffset, backupJob);
                        BackupCompleteness.Detail detail = completenessById.get(obj.backupId);
                        if (detail != null) {
                            applyCompleteness(mapRecord, detail);
                        }
                        return mapRecord;
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            if (backupJob != null) {
                // Attention, the backup is maybe complete, and already in the previous list
                Optional<BackupInfo> first = listBackupResult.listBackups.stream().filter(obj -> obj.backupId == backupJob.getBackupId()).findFirst();
                if (!first.isPresent()) {
                    listBackupMap.add(0, Map.of("backupId", backupJob.getBackupId(),
                            "backupName", "",
                            "backupTime", DateOperation.dateTimeToHumanString(DateOperation.getLocalDateTimeNow(), timezoneOffset),
                            "backupStatus", backupJob.getJobStatus().toString()));
                }
            }
            result.put("listBackups", listBackupMap);

            result.put("listUrlsBackup", listBackupResult.listUrls.entrySet().stream()
                    .map(entry -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", entry.getKey());
                        map.put("url", entry.getValue());
                        return map;
                    })
                    .collect(Collectors.toList()));


            result.put("errorMessage", listBackupResult.listErrors.stream()
                    .map(OperationException::getMessage)
                    .collect(Collectors.joining(";")));
            result.put("error", listBackupResult.listErrors.stream()
                    .map(OperationException::getError)
                    .collect(Collectors.joining(";")));

            return result;
        } catch (OperationException oe) {
            result.put("errorMessage", oe.getMessage());
            result.put("error", oe.getError());
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping(value = "/api/backup/completeness", produces = "application/json")
    public Map<String, Object> backupCompleteness(@RequestParam(name = "timezoneoffset", required = false) Long timezoneOffset) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<BackupCompleteness.Detail> details = backupManager.getBackupCompletenessDetails();
            List<Map<String, Object>> rows = details.stream().map(d -> {
                Map<String, Object> row = new HashMap<>();
                row.put("backupId", d.backupId());
                row.put("complete", d.complete());
                row.put("presentComponents", d.presentComponents());
                row.put("missingComponents", d.missingComponents());
                row.put("backupStatus", d.backupStatus());
                return row;
            }).collect(Collectors.toList());
            result.put("backups", rows);
            return result;
        } catch (OperationException oe) {
            result.put("errorMessage", oe.getMessage());
            result.put("error", oe.getError());
            return result;
        }
    }

    private static Map<String, Object> toBackupMap(BackupInfo obj, Long timezoneOffset, BackupJob backupJob) {
        Map<String, Object> mapRecord = new HashMap<>();
        mapRecord.put("backupId", obj.backupId);
        mapRecord.put("backupName", obj.backupName);
        mapRecord.put("components", obj.components.stream()
                .map(Object::toString)
                .collect(Collectors.toList()));
        mapRecord.put("backupTimeUTC", obj.backupTime);
        mapRecord.put("backupTimeOffset", obj.backupTime.minusMinutes(timezoneOffset));
        mapRecord.put("backupTime", DateOperation.dateTimeToHumanString(obj.backupTime, timezoneOffset));

        mapRecord.put("backupStatus", obj.status == null ? "" : obj.status.toString());
        if (backupJob != null && obj.backupId == backupJob.getBackupId()
                && BackupJob.JOBSTATUS.INPROGRESS.equals(backupJob.getJobStatus())) {
            mapRecord.put("backupStatus", BackupJob.JOBSTATUS.INPROGRESS.toString());
        }
        return mapRecord;
    }

    private static void applyCompleteness(Map<String, Object> mapRecord, BackupCompleteness.Detail detail) {
        mapRecord.put("complete", detail.complete());
        mapRecord.put("presentComponents", detail.presentComponents());
        mapRecord.put("missingComponents", detail.missingComponents());
    }

    private class MonitorStatus {
        @JsonProperty
        String statusBackup;
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
        List<OperationLog.Message> messages;
    }
}
