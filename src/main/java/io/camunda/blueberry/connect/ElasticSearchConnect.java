package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.container.Container;
import io.camunda.blueberry.connect.container.ContainerFactory;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.ElasticsearchException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ElasticSearchConnect implements BackupComponentInt {
    Logger logger = LoggerFactory.getLogger(ElasticSearchConnect.class);

    BlueberryConfig blueberryConfig;

    ContainerFactory containerFactory;

    @Autowired
    private RestTemplate restTemplate; // Injected instance

    public ElasticSearchConnect(BlueberryConfig blueberryConfig, ContainerFactory containerFactory) {
        this.blueberryConfig = blueberryConfig;
        this.containerFactory = containerFactory;
    }

    public OperationResult connection() {
        OperationResult operationResult = new OperationResult();
        operationResult.success = true;
        return operationResult;
    }

    public boolean isConnected() {
        String url = blueberryConfig.getElasticsearchUrl();
        try {
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);
            // receive {"operaterepository":{"type":"azure","uuid":"7BlNbbw3TxadAqc6M523cw","settings":{"container":"elasticsearchcontainer","base_path":"operatebackup"}}}
            if (response.getStatusCode() != HttpStatus.OK) {
                logger.error("Elasticsearch connection failed with status " + response.getStatusCode());
                return false;
            }
            logger.info("Elasticsearch connection successful");
            return true;
        } catch (Exception e) {
            logger.error("Can't access ElasticSearch url[{}] {} ", url, e);
            return false;
        }
    }

    /**
     * Return the connection information plus informatin on the way to connect, in order to give back more feedback
     *
     * @return
     */
    public CamundaApplicationInt.ConnectionInfo isConnectedInformation() {
        return new CamundaApplicationInt.ConnectionInfo(isConnected(), "Url Connection [" + blueberryConfig.getElasticsearchUrl() + "]");
    }

    public OperationResult existRepository(String repositoryName) {
        OperationResult operationResult = new OperationResult();
        String url = blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repositoryName;
        operationResult.command = "PUT " + url;

        try {
            // ResponseEntity<String> response = restTemplate.getForEntity(operationResult.command, String.class);
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);
            // receive {"operaterepository":{"type":"azure","uuid":"7BlNbbw3TxadAqc6M523cw","settings":{"container":"elasticsearchcontainer","base_path":"operatebackup"}}}
            if (response.getStatusCode() != HttpStatus.OK) {
                operationResult.success = false;
                operationResult.details = "Can't connect to ElasticSearch [" + operationResult.command + "]: Http " + response.getStatusCode().value();
                return operationResult;
            }

            JsonNode jsonNode = response.getBody();
// is a repository is defined?
            String containerType = jsonNode == null ? null : jsonNode.path("operaterepository").path("type").asText();
            String containerUuid = jsonNode == null ? null : jsonNode.path("operaterepository").path("uuid").asText();
            operationResult.details = " ContainerType[" + containerType + "] uuid[" + containerUuid + "]";

            operationResult.resultBoolean = true;
            operationResult.success = true;

            return operationResult;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                operationResult.success = false;
                operationResult.details = "Repository [" + repositoryName + "] does not exist";
            } else {
                operationResult.success = false;
                operationResult.details = e.getMessage();
            }
            return operationResult; // If an exception occurs (e.g., 404 error), assume the repository doesn't exist
        } catch (Exception e) {
            operationResult.success = false;
            operationResult.details = e.getMessage();
            return operationResult; // If an exception occurs (e.g., 404 error), assume the repository doesn't exist
        }
    }

    /**
     * Create a repository with the command
     * <p>
     * To delete it: curl -X DELETE "http://localhost:9200/_snapshot/operaterepository"
     *
     * @param repositoryName
     * @param containerType           azure, S3...
     * @param basePathInsideContainer inside the container, the path
     * @throws ElasticsearchException exception in case of error
     */
    public OperationResult createRepository(String repositoryName,
                                            String containerType,
                                            String basePathInsideContainer) {
        OperationResult operationResult = new OperationResult();
        operationResult.command = blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repositoryName;
        Container container = containerFactory.getContainerFromType(containerType);
        if (container == null) {
            operationResult.success = false;
            operationResult.details = "Can't find container [" + containerType + "]";
            return operationResult;
        }

        String jsonPayload = container.getElasticsearchPayload(basePathInsideContainer);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Create HTTP entity with payload and headers
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonPayload, headers);

            // Make PUT request
            ResponseEntity<String> response = restTemplate.exchange(operationResult.command, HttpMethod.PUT, requestEntity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                operationResult.success = false;
                operationResult.details = "Can't create repository : code["
                        + response.getStatusCode().value() + " RepositoryName[" + repositoryName
                        + "] using container information[" + container.getInformation()
                        + "] Type[" + containerType + "] path[" + basePathInsideContainer + "] " + response.getBody();
                return operationResult;
            }

            operationResult.success = true;
            return operationResult;

        } catch (Exception e) {
            operationResult.success = false;
            operationResult.details = "Can't create repository : RepositoryName[" + repositoryName
                    + "] using container name[" + container.getInformation()
                    + "] Type[" + containerType + "] path[" + basePathInsideContainer + "] : "
                    + e.getMessage();
            return operationResult;
        }

    }


    @Override
    public boolean isActive() {
        return blueberryConfig.getElasticsearchUrl() != null;
    }

    @Override
    public CamundaApplicationInt.COMPONENT getComponent() {
        return CamundaApplicationInt.COMPONENT.ZEEBERECORD;
    }

    /***
     *      * curl -X PUT http://localhost:9200/_snapshot/zeeberecordrepository/12 -H 'Content-Type: application/json'   \
     *      * -d '{ "indices": "zeebe-record*", "feature_states": ["none"]}'
     * @param backupId
     * @param operationLog
     * @return
     * @throws BackupException
     */
    @Override
    public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
        String zeebeEsRepository = blueberryConfig.getZeebeRecordRepository();
        CamundaApplicationInt.BackupOperation backupOperation = new CamundaApplicationInt.BackupOperation();

        HttpEntity<?> zeebeEsBackupRequest = new HttpEntity<>(Map.of("indices", "zeebe-record*", "feature_states", List.of("none")));
        String urlComplete = blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + blueberryConfig.getZeebeRecordRepository() + "/" + backupId;
        try {
            ResponseEntity<String> zeebeEsBackupResponse = restTemplate.exchange(urlComplete, HttpMethod.PUT, zeebeEsBackupRequest, String.class);
            backupOperation.status = zeebeEsBackupResponse.getStatusCode().value();

            operationLog.info(CamundaApplicationInt.COMPONENT.ZEEBERECORD, "Start Zeebe ES Backup on [" + zeebeEsRepository + "] response: " + zeebeEsBackupResponse.getStatusCode().value() + " [" + zeebeEsBackupResponse.getBody() + "]");
        } catch (Exception e) {
            logger.error("Exception in esBackup", e);

            backupOperation.title = e.getMessage();
            backupOperation.message = "Component[" + CamundaApplicationInt.COMPONENT.ZEEBERECORD + "] Url[" + urlComplete + "]" + e.getMessage();
            operationLog.error(CamundaApplicationInt.COMPONENT.ZEEBERECORD, "Error during backup ZeebeRecord " + e.getMessage());
        }
        return backupOperation;
    }

    @Override
    public BackupOperation waitBackup(Long backupId, OperationLog operationLog) throws BackupException {
        CamundaApplicationInt.BackupOperation backupOperation = new CamundaApplicationInt.BackupOperation();

        String urlComplete = blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + blueberryConfig.getZeebeRecordRepository() + "/" + backupId;
        boolean written = false;
        int timeToWait = 100;
        long totalTimeToWait = 0;
        int loopCount = 0;
        ResponseEntity<JsonNode> response = null;
        boolean backupIsFinished = false;
        do {
            logger.info("checking backup status for url {} count {}", urlComplete, loopCount);
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

            try {
                response = restTemplate.getForEntity(urlComplete, JsonNode.class);
            } catch (Exception e) {
                logger.error("Exception in esBackup", e);
                backupOperation.status = 400;
                backupOperation.title = e.getMessage();
                backupOperation.message = "Component[" + CamundaApplicationInt.COMPONENT.ZEEBERECORD + "] Url[" + urlComplete + "]" + e.getMessage();

                return backupOperation;
            }
            JsonNode jsonNode = response.getBody();
            backupOperation.status = response.getStatusCode().value();

            int status = jsonNode == null ? null : jsonNode.path("status").asInt();

            logger.info("backup status response for url {}: {}, {}", urlComplete, response.getStatusCodeValue(), response.getBody());
            // Check status
            backupIsFinished = true;
            JsonNode snapshots = jsonNode.path("snapshots");
            for (JsonNode snapshot : snapshots) {
                String snapshotName = snapshot.path("snapshot").asText();
                String state = snapshot.path("state").asText();
                if (!"SUCCESS".equals(state)) {
                    backupIsFinished = false;
                }
                if (!written) {
                    operationLog.addSnapshotName(CamundaApplicationInt.COMPONENT.ZEEBERECORD, snapshotName);

                }
            }
            written = true; //only write once (this can surely be done better :-))
        }
        while (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && !backupIsFinished);
        return backupOperation;
    }

    @Override
    public List<BackupInfo> getListBackups() throws OperationException {

        String urlComplete = getUrlListBackup();
        try {
            // http://localhost:9200/_snapshot/camunda_zeebe_records_backup/_all?pretty
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(urlComplete, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful())
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, response.getStatusCode().value(), "Can't access ES", "Can't access ES");

            JsonNode jsonNode = response.getBody();
            JsonNode snapshots = jsonNode.path("snapshots");
            List<BackupInfo> listBackups = new ArrayList<>();
            for (JsonNode snapshot : snapshots) {
                BackupInfo backupInfo = new BackupInfo();

                String state = snapshot.path("state").asText();
                backupInfo.status = "SUCCESS".equals(state) ? BackupInfo.Status.COMPLETED : BackupInfo.Status.FAILED;
                backupInfo.backupId = snapshot.path("snapshot").asInt();
                backupInfo.components.add(CamundaApplicationInt.COMPONENT.ZEEBERECORD);
                // search the date in the first partition
                // value is 2025-05-29T00:16:55.622+0000
                String timestamp = snapshot.path("start_time").asText();
                backupInfo.backupTime = Instant.parse(timestamp)
                        .atZone(ZoneId.systemDefault()) // Use your system's time zone
                        .toLocalDateTime();
                listBackups.add(backupInfo);
            }
            return listBackups;
        } catch (Exception e) {
            logger.error("Can't call [{}] error {}", urlComplete, e);
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "No Connection to ElasticSearch", "Error url[" + urlComplete + "] : " + e.getMessage());
        }
    }
    @Override
    public String getUrlListBackup() {
        return blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + blueberryConfig.getZeebeRecordRepository() + "/_all";

    }
}
