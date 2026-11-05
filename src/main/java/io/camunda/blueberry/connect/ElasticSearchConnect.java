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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

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
     * @return the connection info
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
        // Only when blueberry.elasticsearch.enabled is true
        return blueberryConfig.isElasticsearchEnabled() && blueberryConfig.getElasticsearchUrl() != null;
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

    /**
     * Wait for the backup to finish. Wait for ever
     *
     * @param backupId
     * @param operationLog
     * @return
     * @throws BackupException
     */
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

            logger.info("backup status response for url {}: {}, {}", urlComplete, response.getStatusCode().value(), response.getBody());
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

    /* ******************************************************************** */
    /*                                                                      */
    /*  Data indexes query                                                  */
    /*                                                                      */
    /* ******************************************************************** */


    /**
     * get the list of ZeebeRecords
     * ZeebeRecords store information group by ¨Partition/<TypeSequence>/<Value>
     * for example 1/PROCESS_INSTANCE/4553
     *
     * @return a Map of Partitions. For each partition SequencePerType is a map<Sequence,Value>
     */
    public PartitionSequencesInfo getZeebeRecordsInfo() throws OperationException {
        String uri = "/zeebe-record*/_search";
        String dataOption = "" // EOL
                + "{\"size\": 0, " // EOL
                + " \"aggs\": "// EOL
                + "  {\"value_types\": " // EOL
                + "   {\"terms\": {\"field\": \"valueType\",\"size\": 20}," // EOL
                + "    \"aggs\": {\"partitions\": " + "                {\"terms\": {\"field\": \"partitionId\"},"  // EOL
                + "                 \"aggs\": " // EOL
                + "                  { "
                + "                    \"min_sequence\": {\"min\": " + "{\"field\": \"sequence\"}}," // EOL
                + "                    \"max_sequence\": {\"max\": {\"field\": \"sequence\"}},"
                + "                     \"min_position\": {\"min\": " + "{\"field\": \"position\"}}," // EOL
                + "                    \"max_position\": {\"max\": {\"field\": \"position\"}}"
                + "                  }" // EOL
                + "                }" // EOL
                + "              }" // EOD
                + "   }" // EOL
                + "  }" // EOL
                + "}";

        logger.info("ElasticSearch getZeebeRecord URL[{}] Body[{}]", blueberryConfig.getElasticsearchUrl() + uri, dataOption);
        try {
        /*
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // entity (body + headers)
        HttpEntity<String> entity = new HttpEntity<>(dataOption, headers);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(urlComplete, entity, JsonNode.class);
        */
            WebClient webClient = WebClient.builder()
                    .baseUrl(blueberryConfig.getElasticsearchUrl())
                    .build();

            Mono<JsonNode> response = webClient.post()
                    .uri(uri)  // relative path
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(dataOption) // request body
                    .retrieve()
                    .bodyToMono(JsonNode.class);

            // Block here just for demo (otherwise you’d stay reactive)
            JsonNode jsonResponse = response.block();

            PartitionSequencesInfo partitionSequencesPerType = new PartitionSequencesInfo();
            JsonNode buckets = jsonResponse.path("aggregations").path("value_types").path("buckets");


            // search key "process-instance
            if (buckets.isArray()) {
                for (JsonNode bucket : buckets) {
                    // Access the source of each hit and convert it to a Map
                    // typeSequence is PROCESS_INSTANCE, PROCESS_INSTANCE_CREATION, PROCESS, INCIDENT, VARIABLE, JOB, COMMAND_DISTRIBUTION, DEPLOYMENT
                    String typeSequence = bucket.get("key").asText();
                    if (typeSequence == null)
                        continue;

                    JsonNode partitions = bucket.path("partitions").path("buckets");
                    if (partitions.isArray()) {
                        for (JsonNode partition : partitions) {
                            Integer partitionId = partition.get("key").asInt();
                            JsonNode maxSequence = partition.path("max_sequence");
                            Long valueSequence = maxSequence.get("value").asLong();
                            JsonNode maxPosition = partition.path("max_position");
                            Long valuePosition = maxPosition.get("value").asLong();

                            SequencesPerType sequencesPerType = partitionSequencesPerType.getPartitionSequence(partitionId);
                            sequencesPerType.addSequence(typeSequence, valueSequence, valuePosition);
                            partitionSequencesPerType.setPartitionSequence(partitionId, sequencesPerType);


                        }
                    }
                }
            }
            return partitionSequencesPerType;
        } catch (Exception e) {
            logger.error("Can't call [{}] error {}",  blueberryConfig.getElasticsearchUrl() + uri, e);
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.ZEEBERECORDS_INFO, "No Connection to ElasticSearch", "Error url[" +  blueberryConfig.getElasticsearchUrl() + uri + "] : " + e.getMessage());

        }
    }


    //  curl -o optimize-import-position.json --location 'http://<opensearchHost>:<opensearchPort>/optimize-position-based-import-index*/_search?size=100&pretty=true'
    // Operate and Tasklist import positions are no longer checked: since 8.10 they are in the RDBMS.

    public enum COMPONENT_IMPORTER {OPTIMIZE}


    /**
     * get <Component> sequence. Same as ZeebeRecord, but stored in a in a different way
     *
     * @return a Map of Partitions. For each partition SequencePerType is a map<Sequence,Value>
     */
    public PartitionSequencesInfo getComponentRecordsInfo(COMPONENT_IMPORTER component) throws OperationException {

        String urlComplete = null;
        switch (component) {
            case OPTIMIZE:
                urlComplete = blueberryConfig.getElasticsearchUrl() + "/optimize-position-based-import-index*/_search?size=500";
                break;
            default:
                throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.NOT_SUPPORTED,
                        component.name() + " Not supported",
                        "This component [" + component.name() + "] is not supported");
        }
        try {

            WebClient webClient = WebClient.builder()
                    .baseUrl(blueberryConfig.getElasticsearchUrl())
                    .build();

            // relative path of the component index, the baseUrl is the Elasticsearch url
            Mono<JsonNode> response = webClient.get()
                    .uri(urlComplete.substring(blueberryConfig.getElasticsearchUrl().length()))
                    .retrieve()
                    .bodyToMono(JsonNode.class);

            // Block here just for demo (otherwise you’d stay reactive)
            JsonNode jsonResponse = response.block();

            PartitionSequencesInfo partitionSequencesPerType = new PartitionSequencesInfo();


            JsonNode hits = jsonResponse.path("hits").path("hits");
            // search key "process-instance
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    // Access the source of each hit and convert it to a Map
                    JsonNode source = hit.path("_source");
                    String typeSequence = source.get("id").asText("");

                    StringTokenizer st = new StringTokenizer(typeSequence, "-");
                    int partitionId = Integer.parseInt(st.nextToken());
                    Long valueSequence = source.get("sequence").asLong();
                    Long valuePosition = source.get("position").asLong();

                    SequencesPerType sequencesPerType = partitionSequencesPerType.getPartitionSequence(partitionId);
                    sequencesPerType.addSequence(typeSequence, valueSequence, valuePosition);
                    partitionSequencesPerType.setPartitionSequence(partitionId, sequencesPerType);


                }
            }
            return partitionSequencesPerType;
        } catch (Exception e) {
            logger.error("Can't call [{}] error {}", urlComplete, e);
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.OPERATEINDEXES_INFO, "No Connection to ElasticSearch", "Error url[" + urlComplete + "] : " + e.getMessage());

        }

    }


    /* ******************************************************************** */
    /*                                                                      */
    /*  PartitionSequencesInfo                                              */
    /*                                                                      */
    /* ******************************************************************** */

    public class PartitionSequencesInfo {
        public Map<Integer, SequencesPerType> record = new HashMap<>();

        public Map<Integer, SequencesPerType> getAllPartitions() {
            return record;
        }

        public SequencesPerType getPartitionSequence(Integer partitionId) {
            if (!record.containsKey(partitionId))
                record.put(partitionId, new SequencesPerType());
            return record.get(partitionId);
        }

        public void setPartitionSequence(Integer partitionId, SequencesPerType sequence) {
            record.put(partitionId, sequence);
        }

        /**
         * getAllSequence and return the max
         *
         * @param partitionId for a specific partition
         * @return the max
         */

        public long getMaxSequence(int partitionId) {
            SequencesPerType sequencePartition = getPartitionSequence(partitionId);
            return sequencePartition.getInfo()
                    .values()
                    .stream()
                    .mapToLong(SequenceInfo::sequence)
                    .max()
                    .orElse(0L);

        }

        public long getMaxPosition(int partitionId) {
            SequencesPerType sequencePartition = getPartitionSequence(partitionId);
            return sequencePartition.getInfo()
                    .values()
                    .stream()
                    .mapToLong(SequenceInfo::position)
                    .max()
                    .orElse(0L);

        }
    }


    /* ******************************************************************** */
    /*                                                                      */
    /*  SequencesPerType                                                    */
    /*                                                                      */
    /* ******************************************************************** */
    public class SequencesPerType {
        private Map<String, SequenceInfo> infoType = new HashMap<>();

        public void addSequence(String typeSequence, Long sequence, Long position) {
            infoType.put(typeSequence, new SequenceInfo(sequence, position));

        }

        public Map<String, SequenceInfo> getInfo() {
            return infoType;
        }
    }

    public record SequenceInfo(
            long sequence,
            long position) {}

}
