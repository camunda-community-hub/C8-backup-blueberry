package io.camunda.blueberry.connect.toolbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.blueberry.connect.ActuatorBackupStatusResponse;
import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WebActuator {
    Logger logger = LoggerFactory.getLogger(WebActuator.class);
    @Autowired
    private RestTemplate restTemplate;

    public WebActuator(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }


    public boolean isConnected(CamundaApplicationInt.COMPONENT component, String url) {
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
    public CamundaApplicationInt.BackupOperation startBackup(CamundaApplicationInt.COMPONENT component, Long backupId, String url, OperationLog operationLog) {
        Map<String, Object> backupBody = java.util.Map.of("backupId", backupId);

        String urlComplete = url + "/actuator/backups";
        CamundaApplicationInt.BackupOperation backupOperation = new CamundaApplicationInt.BackupOperation();
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
                operationLog.info(component, String.format("backup [%d] url[%s] Message[%s]", backupId, url, backupOperation.message));
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
     * Check the backup status
     *
     * @param component    component to communicate with
     * @param backupId     Backup ID
     * @param url          complete url to start the backup command in the component
     * @param operationLog log the operation
     */
    public CamundaApplicationInt.BackupOperation waitBackup(CamundaApplicationInt.COMPONENT component, long backupId, String url,
                                                            OperationLog operationLog) {
        CamundaApplicationInt.BackupOperation backupOperation = new CamundaApplicationInt.BackupOperation();
        boolean written = false;
        int timeToWait = 100;
        long totalTimeToWait = 0;
        int loopCount = 0;
        ResponseEntity<ActuatorBackupStatusResponse> backupStatusResponse = null;
        do {
            logger.info("checking backup status for url {} count {}", url, loopCount);
            loopCount++;
            if (totalTimeToWait > 3000) {
                timeToWait = 500;
            }
            if (totalTimeToWait > 10000) {
                timeToWait = 1000;
            }
            if (totalTimeToWait > 30000) {
                timeToWait = 5000;
            }

            try {
                Thread.sleep(timeToWait);
                totalTimeToWait += timeToWait;

            } catch (InterruptedException e) {
                // do nothing
            }
            backupStatusResponse = restTemplate.getForEntity(url + "/actuator/backups/" + backupId, ActuatorBackupStatusResponse.class);
            logger.info("backup status response for url {}: {}, {}", url, backupStatusResponse.getStatusCodeValue(), backupStatusResponse.getBody());
            backupOperation.status = backupStatusResponse.getStatusCode().value();
            if (!written) {
                for (int i = 0; i < backupStatusResponse.getBody().getDetails().size(); i++) {
                    String csvSnapshotName = backupStatusResponse.getBody().getDetails().get(i).getSnapshotName();
                    operationLog.addSnapshotName(component, csvSnapshotName);
                }
                written = true; //only write once (this can surely be done better :-))
            }

        } while (backupStatusResponse.getStatusCode().is2xxSuccessful()
                && backupStatusResponse.getBody() != null
                && !backupStatusResponse.getBody().getState().equals("COMPLETED")
                && loopCount < 1000);
        return backupOperation;

    }

    /**
     * Check the backup status
     *
     * @param component component to communicate with
     * @param url       complete url to start the backup command in the component
     */
    public List<BackupInfo> getListBackups(CamundaApplicationInt.COMPONENT component, String url) throws OperationException {
        try {

            ResponseEntity<ActuatorBackupStatusResponse[]> backupListResponse = restTemplate.getForEntity(url, ActuatorBackupStatusResponse[].class);
            logger.info("backup {} status response for url {}: {}, {}", component.name(), url, backupListResponse.getStatusCodeValue(), backupListResponse.getBody());
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
                            backupInfo.status=BackupInfo.Status.UNKNOWN;
                        }
                        // search the date in the first partition
                        if (t.getDetails().size() > 1) {
                            // value is 2025-05-29T00:16:55.622+0000
                            String timestamp = t.getDetails().get(0).getStartTime();
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

                            ZonedDateTime zonedDateTime = ZonedDateTime.parse(timestamp, formatter);
                            backupInfo.backupTime = zonedDateTime.toLocalDateTime();

                        }
                        return backupInfo;
                    }).toList();
        } catch (Exception e) {
            logger.error("Compoment {} Can't call [{}] error {}", component, url, e);
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "No communication to "+component,
                    component + ": Error url[" + url + "] : " + e.getMessage());
        }
    }


}
