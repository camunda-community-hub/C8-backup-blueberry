package io.camunda.blueberry.connect.toolbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.blueberry.connect.ActuatorBackupStatusResponse;
import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.exception.SnapshotRepositoryError;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WebActuator {
    /**
     * How long a component can answer 404 to the status of a backup before the backup is considered as not created
     */
    static final long NOT_FOUND_TIMEOUT_MILLIS = 120_000;
    /**
     * After the start of a backup, how long the component has to know it. The component accepts the request (202) and creates the
     * snapshots asynchronously: if they fail (for example an index of Optimize is missing), the backup never exists
     */
    static final long CONFIRM_TIMEOUT_MILLIS = 15_000;
    static final long CONFIRM_POLL_MILLIS = 1_000;
    // package visible: the tests wait less
    long confirmTimeoutMillis = CONFIRM_TIMEOUT_MILLIS;
    long confirmPollMillis = CONFIRM_POLL_MILLIS;
    Logger logger = LoggerFactory.getLogger(WebActuator.class);
    @Autowired
    private RestTemplate restTemplate;

    public WebActuator(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }


    public boolean isConnected(BackupComponentInt.COMPONENT component, String url) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.getForObject(url, String.class);
            logger.info("Check connection component [{}] url [{}] with success", component, url);
            return true;
        } catch (Exception e) {
            logger.error("Can't connect Component [{}] url[{}] {}", component, url, e);
            return false;
        }
    }


    /**
     * Start the backup command
     * Example curl -X POST http://localhost:8081/actuator/backup -H "Content-Type: application/json" -d '{"backupId": 12}'
     *
     * @param component    component to communicate with
     * @param backupId     Backup ID
     * @param url          complete url to start the backup command in the component
     * @param operationLog log the operation
     */
    public BackupComponentInt.BackupOperation startBackup(BackupComponentInt.COMPONENT component, Long backupId, String url, OperationLog operationLog) {
        Map<String, Object> backupBody = java.util.Map.of("backupId", backupId);

        String urlComplete = url + "/actuator/backups";
        BackupComponentInt.BackupOperation backupOperation = new BackupComponentInt.BackupOperation();
        try {
            logger.info("StartBackup component[{}] with url[{}] body: {} ", component, urlComplete, backupBody);
            // We use a JsonNode to be more robust, in case the result change in a version. So, we can explore the JSON result.
            ResponseEntity<JsonNode> backupResponse = restTemplate.postForEntity(urlComplete, backupBody, JsonNode.class);

            backupOperation.status = backupResponse.getStatusCode().value();
            if (backupOperation.status != 200 && backupOperation.status != 202) {
                operationLog.error(component, "backup [" + backupId + "] status[" + backupOperation.status + "] url [" + url + "]");
                return backupOperation;
            }
            // Extract the list of Snapshoot
            JsonNode bodyJson = backupResponse.getBody();
            if (bodyJson.has("scheduledSnapshots")) { // Check if scheduledSnapshots exists
                JsonNode snapshotsNode = bodyJson.get("scheduledSnapshots");

                if (snapshotsNode != null && snapshotsNode.isArray()) {
                    for (JsonNode node : snapshotsNode) {
                        backupOperation.listSnapshots.add(node.asText());
                    }
                }
                operationLog.info(component, "backup [" + backupId + "] url[" + url + "] ListBackup[" + backupOperation.listSnapshots + "]");
            } else if (bodyJson.has("message")) { // Handle Optimize case where "message" exists
                backupOperation.message = bodyJson.get("message").asText();
                operationLog.info(component, String.format("backup started [%d] url[%s] Message[%s]", backupId, url, backupOperation.message));
            } else {
                operationLog.warning(component, String.format("backup [%d] url[%s] Unexpected response: %s", backupId, url, bodyJson));
            }
        } catch (Exception e) {
            OperationException operationException = OperationException.getInstanceFromException(OperationException.BLUEBERRYERRORCODE.BACKUP, e);
            backupOperation.title = operationException.getError();
            backupOperation.message = "Component[" + component.toString() + "] Url[" + urlComplete + "]" + operationException.getMessage();
            operationLog.error(component, "Component[" + component + "] backupId[" + backupId + "] url[" + url + "] Error[" + operationException.getMessage() + "]");
        }
        return backupOperation;
    }

    /**
     * The component accepted the start of the backup (202): is the backup created? The component creates its snapshots in the background and the
     * request does not say if they fail. If the component does not know the backup after a few seconds, the backup failed: stop here with a clear
     * status, instead of waiting for a backup which will never exist.
     *
     * @param started the result of the start of the backup
     * @return the result of the start if the component knows the backup, else a failure
     */
    public BackupComponentInt.BackupOperation confirmBackupCreated(BackupComponentInt.COMPONENT component, long backupId, String url,
                                                                   OperationLog operationLog,
                                                                   BackupComponentInt.BackupOperation started) {
        long waited = 0;
        while (true) {
            try {
                ResponseEntity<ActuatorBackupStatusResponse> response = restTemplate.getForEntity(url + "/actuator/backups/" + backupId,
                        ActuatorBackupStatusResponse.class);
                ActuatorBackupStatusResponse body = response.getBody();
                if (body != null && "FAILED".equalsIgnoreCase(body.getState())) {
                    return notCreated(component, backupId, operationLog, "the backup is FAILED");
                }
                // the component knows the backup
                return started;
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                logger.info("Component {} does not know the backup [{}] yet (404) url [{}]", component, backupId, url);
                if (waited >= confirmTimeoutMillis) {
                    return notCreated(component, backupId, operationLog, "the backup does not exist after " + (confirmTimeoutMillis / 1000) + " seconds");
                }
            } catch (Exception e) {
                // the component can't be asked: the wait of the end of the backup will check
                logger.warn("Can't check that component {} knows the backup [{}]: {}", component, backupId, e.getMessage());
                return started;
            }
            try {
                Thread.sleep(confirmPollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return started;
            }
            waited += confirmPollMillis;
        }
    }

    private BackupComponentInt.BackupOperation notCreated(BackupComponentInt.COMPONENT component, long backupId, OperationLog operationLog, String reason) {
        BackupComponentInt.BackupOperation failed = new BackupComponentInt.BackupOperation();
        failed.status = 500;
        failed.title = "The backup [" + backupId + "] was not created by " + component;
        failed.message = component + " accepted the backup request, but " + reason + ": its snapshots failed. Check the log of " + component
                + " and its indices in Elasticsearch (after a reset of Elasticsearch, restart " + component + " so it creates its indices again).";
        operationLog.error(component, failed.message);
        return failed;
    }

    /**
     * Check the backup status
     *
     * @param component    component to communicate with
     * @param backupId     Backup ID
     * @param url          complete url to start the backup command in the component
     * @param operationLog log the operation
     */
    public BackupComponentInt.BackupOperation waitBackup(BackupComponentInt.COMPONENT component, long backupId, String url,
                                                         OperationLog operationLog) {

        BackupComponentInt.BackupOperation backupOperation = new BackupComponentInt.BackupOperation();
        boolean written = false;
        int timeToWait = 100;
        long totalTimeToWait = 0;
        int loopCount = 0;
        ResponseEntity<ActuatorBackupStatusResponse> backupStatusResponse = null;
        boolean continueLoop = true;
        // The backup is not known by the component (404) since this time. It is normal during the first seconds: the snapshot is created
        // asynchronously. If it lasts, the backup was never created (for example the snapshot repository does not exist)
        long notFoundSince = -1;
        String completeUrl = url + "/actuator/backups/" + backupId;
        do {
            logger.info("checking backup status for url {} count {}", completeUrl, loopCount);
            loopCount++;
            if (totalTimeToWait > 3000) {
                timeToWait = 1000;
            }
            if (totalTimeToWait > 10000) {
                timeToWait = 5000;
            }
            if (totalTimeToWait > 30000) {
                timeToWait = 10000;
            }

            try {
                Thread.sleep(timeToWait);
                totalTimeToWait += timeToWait;

            } catch (InterruptedException e) {
                // do nothing
            }
            try {
                backupStatusResponse = restTemplate.getForEntity(completeUrl, ActuatorBackupStatusResponse.class);
                logger.info("backup status response for url {}: {}, {}", completeUrl, backupStatusResponse.getStatusCode().value(), backupStatusResponse.getBody());
                backupOperation.status = backupStatusResponse.getStatusCode().value();
                notFoundSince = -1;
                if (!written) {
                    for (int i = 0; i < backupStatusResponse.getBody().getDetails().size(); i++) {
                        String csvSnapshotName = backupStatusResponse.getBody().getDetails().get(i).getSnapshotName();
                        operationLog.addSnapshotName(component, csvSnapshotName);
                    }
                    written = true; //only write once (this can surely be done better :-))
                }
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                // 404: the backup is not visible (yet). No stack trace for that
                logger.info("Component {} does not know the backup [{}] yet (404) url [{}]", component, backupId, completeUrl);
                if (notFoundSince < 0) {
                    notFoundSince = totalTimeToWait;
                }
                if (totalTimeToWait - notFoundSince > NOT_FOUND_TIMEOUT_MILLIS) {
                    backupOperation.status = 404;
                    backupOperation.title = "The backup [" + backupId + "] does not exist in " + component;
                    backupOperation.message = component + " does not know the backup [" + backupId + "] after " + (NOT_FOUND_TIMEOUT_MILLIS / 1000)
                            + " seconds via [" + completeUrl + "] : it was not created. Check the snapshot repository (see Configuration tab) and the log of " + component + ". " + e.getMessage();
                    operationLog.error(component, backupOperation.message);
                    return backupOperation;
                }
            } catch (Exception e) {
                logger.error("Can't get actuator backup status for url [{}] ", completeUrl, e);
                backupOperation.status = 404;
                backupOperation.title = "Can't get information on backup [" + backupId + "] Error accessing [" + url + "/actuator/backups/" + backupId + "]";
                backupOperation.message = component + " Url[" + completeUrl + "] can't be accessible:" + e.getMessage();
                operationLog.error(component, backupOperation.message);
                return backupOperation;
            }
            continueLoop = loopCount < 1000;
            if (continueLoop && backupStatusResponse != null) {
                continueLoop = (backupStatusResponse.getStatusCode().is2xxSuccessful()
                        && backupStatusResponse.getBody() != null
                        && !backupStatusResponse.getBody().getState().equals("COMPLETED"));
            }
        } while (continueLoop);
        return backupOperation;

    }

    /**
     * Check the backup status
     *
     * @param component component to communicate with
     * @param url       complete url to start the backup command in the component
     */
    public List<BackupInfo> getListBackups(BackupComponentInt.COMPONENT component, String url) throws OperationException {
        try {

            ResponseEntity<ActuatorBackupStatusResponse[]> backupListResponse = restTemplate.getForEntity(url, ActuatorBackupStatusResponse[].class);
            logger.info("backup {} status response for url {}: {}, {}", component.name(), url, backupListResponse.getStatusCode().value(), backupListResponse.getBody());
            if (!backupListResponse.getStatusCode().is2xxSuccessful())
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, backupListResponse.getStatusCode().value(), "Can't access the Actuator", "Can't access the Actuator");


            List<ActuatorBackupStatusResponse> listBackupStatus = Arrays.asList(backupListResponse.getBody());
            return listBackupStatus.stream()
                    .map(t -> {
                        BackupInfo backupInfo = new BackupInfo();
                        backupInfo.backupId = t.getBackupId();
                        backupInfo.components.add(component);
                        try {
                            backupInfo.status = BackupInfo.Status.valueOf(t.getState());
                        } catch (IllegalArgumentException e) {
                            backupInfo.status = BackupInfo.Status.UNKNOWN;
                        }
                        // search the date in the first partition
                        if (t.getDetails().size() > 1) {
                            // value is 2025-05-29T00:16:55.622+0000
                            String timestamp = t.getDetails().get(0).getStartTime();
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

                            ZonedDateTime zonedDateTime = ZonedDateTime.parse(timestamp, formatter);
                            // UTC: the API subtracts the timezone offset of the user (see DateOperation)
                            backupInfo.backupTime = zonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

                        }
                        return backupInfo;
                    }).toList();
        } catch (Exception e) {
            logger.error("Compoment {} Can't call [{}] error {}", component, url, e);
            // The snapshot repository does not exist: the user must configure the platform
            String missingRepository = SnapshotRepositoryError.detect(e.getMessage());
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "No communication to " + component,
                    missingRepository != null ? SnapshotRepositoryError.message(missingRepository)
                            : component + ": Error url[" + url + "] : " + e.getMessage());
        }
    }


}

