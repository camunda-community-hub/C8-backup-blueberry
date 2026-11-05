package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.toolbox.WebActuator;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.client.CamundaClient;
import io.camunda.client.CamundaClientConfiguration;
import io.camunda.client.api.response.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.StreamSupport;

@Component
public class ZeebeConnect extends WebActuator implements BackupComponentInt {
    private final ObjectMapper objectMapper;
    private final CamundaClient camundaClient;
    private final CamundaClientConfiguration camundaClientConfiguration;
    private final BlueberryConfig blueberryConfig;
    private final RestTemplate restTemplate;
    private final WebActuator webActuator;
    Logger logger = LoggerFactory.getLogger(ZeebeConnect.class);
    private long lastTentativeConnection = 0;

    /**
     * The CamundaClient is created by the Spring Boot SDK starter (camunda.client.* properties)
     */
    public ZeebeConnect(BlueberryConfig blueberryConfig, RestTemplate restTemplate, ObjectMapper objectMapper, CamundaClient camundaClient) {
        super(restTemplate);
        webActuator = new WebActuator(restTemplate);
        this.blueberryConfig = blueberryConfig;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.camundaClient = camundaClient;
        this.camundaClientConfiguration = camundaClient.getConfiguration();
    }

    public boolean connection() {
        boolean isConnected = isConnected();
        logger.info("Connection: already connected {} connect to Grpc[{}]", isConnected, camundaClientConfiguration.getGrpcAddress());

        if (isConnected)
            return true;

        // Update the last connection
        lastTentativeConnection = System.currentTimeMillis();

        try {
            final Topology topology = camundaClient.newTopologyRequest().send().join();
            return true;
        } catch (Exception e) {
            logger.error("During Zeebe connection", e);
            return false;
        }
    }

    public void disconnect() {
    }

    /**
     * Return true if the Zeebe client is connected
     *
     * @return true if connected to the Zeebe engine
     */
    public boolean isConnected() {

        try {
            final Topology topology = camundaClient.newTopologyRequest().send().join();
            return true;

        } catch (Exception e) {
            return false;
        }
    }


    /**
     * Zeebe is already active
     *
     * @return
     */
    public boolean isActive() {
        return true;
    }

    @Override
    public CamundaApplicationInt.COMPONENT getComponent() {
        return CamundaApplicationInt.COMPONENT.ZEEBE;
    }

    /**
     * Return the connection information plus informatin on the way to connect, in order to give back more feedback
     *
     * @return connection information
     */
    public CamundaApplicationInt.ConnectionInfo isConnectedInformation() {
        return new CamundaApplicationInt.ConnectionInfo(isConnected(), "Grpc Connection[" + camundaClientConfiguration.getGrpcAddress() + "]");
    }

    public boolean isConnectedActuator() {
        return webActuator.isConnected(CamundaApplicationInt.COMPONENT.ZEEBE, blueberryConfig.getZeebeActuatorUrl() + "/actuator");
    }

    public CamundaApplicationInt.ConnectionInfo isConnectedActuatorInformation() {
        return new CamundaApplicationInt.ConnectionInfo(isConnectedActuator(), "Actuator Connection[" + blueberryConfig.getZeebeActuatorUrl() + "]");
    }

    public boolean activateConnection() {
        if (isConnected())
            return true;
        if (System.currentTimeMillis() - lastTentativeConnection < 5000)
            return false;
        return connection();
    }

    /**
     * Get the cluster information. if not connected, return null
     *
     * @return cluster information, null in case of issue
     */
    public ClusterInformation getClusterInformation() {
        try {
            if (!activateConnection()) {
                return null;
            }
            final Topology topology = camundaClient.newTopologyRequest().send().join();
            ClusterInformation clusterInformation = new ClusterInformation();
            clusterInformation.clusterSize = topology.getClusterSize();
            clusterInformation.partitionsCount = topology.getPartitionsCount();
            clusterInformation.replicationFactor = topology.getReplicationFactor();
            clusterInformation.camundaVersion = topology.getGatewayVersion();

            return clusterInformation;
        } catch (Exception e) {
            logger.error("Can't get ClusterInformation via newTopologyRequest", e);
            return null;
        }
    }

    public CamundaClientConfiguration getZeebeConfiguration() {
        return camundaClientConfiguration;
    }

    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("zeebeIsConnected", isConnected());
        for (Method method : camundaClientConfiguration.getClass().getMethods()) {
            if (method.getName().startsWith("get") &&
                    method.getParameterCount() == 0 &&
                    !method.getReturnType().equals(void.class)) {
                String propertyName = lowerCaseFirstLetter(method.getName().substring(3));
                try {
                    Object value = method.invoke(camundaClientConfiguration);
                    if (value instanceof Duration)
                        parameters.put(propertyName, ((Duration) value).toString());
                    else
                        parameters.put(propertyName, value);
                } catch (Exception e) {
                    parameters.put(propertyName, "ACCESS_DENIED");
                }
            }
        }
        return parameters;
    }

    private String lowerCaseFirstLetter(String str) {
        if (str == null || str.isEmpty())
            return str;
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    public void pauseExporting(OperationLog operationLog) {
        ResponseEntity<String> pauseZeebeExporting = restTemplate.postForEntity(blueberryConfig.getZeebeActuatorUrl() + "/actuator/exporting/pause", new HashMap<>(), String.class);
        operationLog.info(CamundaApplicationInt.COMPONENT.ZEEBE, "Pause Zeebe exporting");
    }
    /* ******************************************************************** */
    /*                                                                      */
    /*  Resume and pause                                                    */
    /*                                                                      */
    /* ******************************************************************** */

    public void resumeExporting(OperationLog operationLog) {
        ResponseEntity<String> zeebeResumeResponse = restTemplate.postForEntity(blueberryConfig.getZeebeActuatorUrl() + "/actuator/exporting/resume", new HashMap<>(), String.class);
        operationLog.info(CamundaApplicationInt.COMPONENT.ZEEBE, "Resume Zeebe exporting");
    }

    /**
     * Get the exporter status
     *
     * @return true: all exporter are ENABLED. False: one is disabled. null: impossible to say (list is empty)
     * @throws OperationException
     */
    public Boolean getExporterStatus() throws OperationException {
        if (!activateConnection())
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.NO_ZEEBE_CONNECTION, "No connection to Zeebe", "Connection is not established to Zeebe " + blueberryConfig.getZeebeActuatorUrl());
        try {
            ResponseEntity<JsonNode> listExporters = restTemplate.getForEntity(blueberryConfig.getZeebeActuatorUrl() + "/actuator/exporters", JsonNode.class);

            JsonNode listExportersNode = listExporters.getBody();
            if (listExportersNode != null && listExportersNode.isArray()) {
                boolean isRunning = true;
                for (JsonNode exporter : listExportersNode) {
                    String status = exporter.path("status").asText();
                    if ("DISABLED".equals(status))
                        isRunning = false;
                }
                return isRunning;
            }
            return null;
        } catch (Exception e) {
            throw OperationException.getInstanceFromException(OperationException.BLUEBERRYERRORCODE.STATUS_EXPORTER, e);
        }
    }

    @Override
    public CamundaApplicationInt.BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {

        CamundaApplicationInt.BackupOperation backupOperation = new CamundaApplicationInt.BackupOperation();

        // 8.10: runtime backup of the cluster (Zeebe + RDBMS secondary storage), see https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/rdbms/rdbms-backup/#manual-backup
        Map<String, Object> backupBody = Map.of("backupId", backupId);
        ResponseEntity<String> backupResponse = restTemplate.postForEntity(blueberryConfig.getZeebeActuatorUrl() + "/actuator/backupRuntime", backupBody, String.class);

        logger.info("Backup status response for url {}: {}, {}", blueberryConfig.getZeebeActuatorUrl(), backupResponse.getStatusCode().value(), backupResponse.getBody());
        backupOperation.status = backupResponse.getStatusCode().value();

        if (backupResponse.getStatusCode().is2xxSuccessful()) {
            // Backup request was successfully scheduled
            logger.info("Backup {} successfully scheduled.", backupId);
        } else {
            // For any non-2xx status, log the error and resume exporting
            logger.error("Backup {} failed with status {}: {}", backupId, backupResponse.getStatusCode(), backupResponse.getBody());
            backupOperation.message = backupResponse.getBody();
            resumeExporting(operationLog);
        }
        return backupOperation;
    }
    /* ******************************************************************** */
    /*                                                                      */
    /*  Backup section                                                      */
    /*                                                                      */
    /* ******************************************************************** */

    @Override
    public CamundaApplicationInt.BackupOperation waitBackup(Long backupId, OperationLog operationLog) throws BackupException {
        ObjectMapper objectMapper = new ObjectMapper();
        CamundaApplicationInt.BackupOperation backupOperation = new CamundaApplicationInt.BackupOperation();

        int loopCount = 0;
        int timeToWait = 100;
        long totalTimeToWait = 0;

        while (loopCount < 1000) {
            logger.info("Checking backup status for URL {}", blueberryConfig.getZeebeActuatorUrl());
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

            ResponseEntity<String> response = restTemplate.getForEntity(
                    blueberryConfig.getZeebeActuatorUrl() + "/actuator/backups/" + backupId, String.class);
            backupOperation.status = response.getStatusCode().value();

            logger.info("Backup status response for URL {}: {}, {}",
                    blueberryConfig.getZeebeActuatorUrl(), response.getStatusCode().value(), response.getBody());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                try {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    String state = root.path("state").asText();

                    if ("COMPLETED".equalsIgnoreCase(state)) {
                        logger.info("Backup {} is completed. Exiting loop.", backupId);
                        break;
                    }
                } catch (Exception e) {
                    logger.error("Failed to parse backup status response", e);
                }
            }
        }
        return backupOperation;
    }

    /**
     * https://docs.camunda.io/docs/8.7/self-managed/operational-guides/backup-restore/zeebe-backup-and-restore/#list-backups-api
     */

    @Override
    public List<BackupInfo> getListBackups() throws OperationException {
        ResponseEntity<BackupInfo> backupStatusResponse = null;
        try {
            logger.debug("Execute [{}]", blueberryConfig.getZeebeActuatorUrl() + "/actuator/backups");
            ResponseEntity<JsonNode> listResponse = restTemplate.getForEntity(getUrlListBackup(), JsonNode.class);
            JsonNode jsonArray = listResponse.getBody();
            if (jsonArray == null)
                throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "Can't connect",
                        "No answer from [" + blueberryConfig.getZeebeActuatorUrl() + "/actuator/backups]");

            List<BackupInfo> listBackupInfo = StreamSupport.stream(jsonArray.spliterator(), false)
                    .map(t -> {
                        BackupInfo backupInfo = new BackupInfo();
                        backupInfo.backupId = t.get("backupId").asInt();
                        backupInfo.components.add(CamundaApplicationInt.COMPONENT.ZEEBE);
                        backupInfo.status = BackupInfo.fromZeebeStatus(t.get("state").asText());
                        // search the date in the first partition
                        JsonNode[] details = objectMapper.convertValue(t.get("details"), JsonNode[].class);

                        String timestamp = details[0].get("createdAt").asText();
                        LocalDateTime localDateTime = Instant.parse(timestamp)
                                .atZone(ZoneId.systemDefault()) // Convert to system's time zone
                                .toLocalDateTime();

                        backupInfo.backupTime = localDateTime;
                        return backupInfo;
                    }).toList();

            logger.info("Execute [{}] found {} backups", blueberryConfig.getZeebeActuatorUrl() + "/actuator/backups", listBackupInfo.size());

            return listBackupInfo;
        } catch (Exception e) {
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "No Connection to Zeebe",
                    "Error url[" + blueberryConfig.getZeebeActuatorUrl() + "/actuator/backups" + "] : " + e.getMessage());

        }
    }

    @Override
    public String getUrlListBackup() {
        return blueberryConfig.getZeebeActuatorUrl() + "/actuator/backups";
    }

    /* ******************************************************************** */
    /*                                                                      */
    /*  Administration                                                      */
    /*                                                                      */
    /* ******************************************************************** */


    /**
     * call :actuator/partitions
     *
     * @return
     * @throws OperationException
     */
    public List<PartitionInfo> getPartitionInformation(int brokerId) throws OperationException {
        ResponseEntity<BackupInfo> backupStatusResponse = null;
        String url = blueberryConfig.getZeebeBrokerActuatorUrl(brokerId) + "/actuator/partitions";
        try {
            logger.info("ZeebeConnect: getPartitionInformation Execute [{}]", url);
            ResponseEntity<JsonNode> listResponse = restTemplate.getForEntity(url, JsonNode.class);
            JsonNode jsonArray = listResponse.getBody();
            if (jsonArray == null)
                throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.PARTITIONS_INFO, "Can't connect",
                        "No answer from [" + blueberryConfig.getZeebeActuatorUrl() + "/actuator/partitions]");

            List<PartitionInfo> listPartitionInfo = new ArrayList<>();

            Iterator<String> fieldNames = jsonArray.fieldNames();
            while (fieldNames.hasNext()) {
                String partitionId = fieldNames.next();
                JsonNode partitionNode = jsonArray.get(partitionId);
                PartitionInfo partitionInfo = new PartitionInfo();
                partitionInfo.brokerId = brokerId;
                partitionInfo.partitionId = Integer.parseInt(partitionId);
                partitionInfo.role = partitionNode.get("role").asText();
                partitionInfo.processedPosition = partitionNode.get("processedPosition").asLong();
                partitionInfo.processedPositionInSnapshot = partitionNode.get("processedPositionInSnapshot").asLong();
                partitionInfo.exportedPosition = partitionNode.get("exportedPosition").asLong();
                listPartitionInfo.add(partitionInfo);
            }

            logger.info("Execute [{}] broker {} found {} partition", url + "/actuator/backups", brokerId, listPartitionInfo.size());

            return listPartitionInfo;
        } catch (Exception e) {
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "No Connection to Zeebe",
                    "Error url[" + url + "] : " + e.getMessage());

        }
    }

    /* ******************************************************************** */
    /*                                                                      */
    /*  getInformation                                                      */
    /*                                                                      */
    /* ******************************************************************** */
    public class ClusterInformation {
        public int clusterSize;
        public int partitionsCount;
        public int replicationFactor;
        public String camundaVersion;

        public Map<String, Object> toMap() {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.convertValue(this, Map.class);
        }
    }

    public class PartitionInfo {
        public int brokerId;
        public int partitionId;
        public String role;
        public Long processedPosition;
        public Long processedPositionInSnapshot;
        public Long exportedPosition;

    }

}
