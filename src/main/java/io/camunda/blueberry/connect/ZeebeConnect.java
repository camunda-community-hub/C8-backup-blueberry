package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.secondary.SecondaryStorageDetector;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

@Component
public class ZeebeConnect extends WebActuator implements BackupComponentInt {
    private final ObjectMapper objectMapper;
    private final CamundaClient camundaClient;
    private final CamundaClientConfiguration camundaClientConfiguration;
    private final BlueberryConfig blueberryConfig;
    private final RestTemplate restTemplate;
    private final WebActuator webActuator;
    private final ZeebeRestApi zeebeRestApi;
    private final SecondaryStorageDetector secondaryStorageDetector;
    Logger logger = LoggerFactory.getLogger(ZeebeConnect.class);
    private long lastTentativeConnection = 0;

    /**
     * The CamundaClient is created by the Spring Boot SDK starter (camunda.client.* properties)
     */
    public ZeebeConnect(BlueberryConfig blueberryConfig, RestTemplate restTemplate, ObjectMapper objectMapper, CamundaClient camundaClient,
                        ZeebeRestApi zeebeRestApi, SecondaryStorageDetector secondaryStorageDetector) {
        super(restTemplate);
        webActuator = new WebActuator(restTemplate);
        this.zeebeRestApi = zeebeRestApi;
        this.secondaryStorageDetector = secondaryStorageDetector;
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
            // detected on the cluster, else the value of the configuration (blueberry.secondaryStorage)
            SecondaryStorageDetector.Detection detection = secondaryStorageDetector.detect();
            clusterInformation.secondaryStorage = detection.value();
            clusterInformation.secondaryStorageSource = detection.source();

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
    /*  Resume and pause (REST API: POST /v2/exporting/pause?soft=true)     */
    /*                                                                      */
    /* ******************************************************************** */

    /**
     * Soft pause of the exporting: the records are still exported, but not deleted (no log compaction).
     * It is the first step of a backup.
     */
    public void softPauseExporting(Long backupId, OperationLog operationLog) throws BackupException {
        ZeebeRestApi.RestResult result = zeebeRestApi.pauseExporting(true);
        if (!result.isSuccess()) {
            operationLog.error(CamundaApplicationInt.COMPONENT.ZEEBE, "Soft pause of the exporting failed: status " + result.status() + " " + result.raw());
            throw new BackupException(CamundaApplicationInt.COMPONENT.ZEEBE, result.status() == 0 ? 500 : result.status(),
                    "Can't pause the Zeebe exporting",
                    "POST " + zeebeRestApi.getBaseUrl() + "/exporting/pause?soft=true answered " + result.status() + " " + result.raw(),
                    backupId);
        }
        operationLog.info(CamundaApplicationInt.COMPONENT.ZEEBE, "Soft pause of the Zeebe exporting");
    }

    /**
     * Resume the exporting. This does not fail: a failure is logged (the backup must not hide its own result)
     */
    public void resumeExporting(OperationLog operationLog) {
        ZeebeRestApi.RestResult result = zeebeRestApi.resumeExporting();
        if (result.isSuccess()) {
            operationLog.info(CamundaApplicationInt.COMPONENT.ZEEBE, "Resume Zeebe exporting");
        } else {
            operationLog.error(CamundaApplicationInt.COMPONENT.ZEEBE, "Can't resume the Zeebe exporting: status " + result.status() + " " + result.raw()
                    + ". Resume it manually: POST " + zeebeRestApi.getBaseUrl() + "/exporting/resume");
        }
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

        // Runtime backup of the Zeebe brokers: REST API POST /v2/backups/runtime {"backupId": n} (202: scheduled)
        ZeebeRestApi.RestResult result = zeebeRestApi.takeRuntimeBackup(backupId);
        logger.info("Zeebe runtime backup {} answered {}: {}", backupId, result.status(), result.raw());
        backupOperation.status = result.status();

        if (result.isSuccess()) {
            logger.info("Backup {} successfully scheduled.", backupId);
            operationLog.info(CamundaApplicationInt.COMPONENT.ZEEBE, "Zeebe runtime backup " + backupId + " scheduled");
        } else {
            logger.error("Backup {} failed with status {}: {}", backupId, result.status(), result.raw());
            backupOperation.title = "Zeebe backup refused (status " + result.status() + ")";
            backupOperation.message = result.raw();
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
            logger.info("Checking backup status for URL {}/{}", getUrlListBackup(), backupId);
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

            // REST API: GET /v2/backups/runtime/{backupId}. 404: not yet visible, the next check will find it
            ZeebeRestApi.RestResult result = zeebeRestApi.getRuntimeBackup(backupId);
            backupOperation.status = result.status();

            logger.info("Backup status response for URL {}/{}: {}, {}", getUrlListBackup(), backupId, result.status(), result.raw());

            if (result.isSuccess() && result.body() != null) {
                String state = result.body().path("state").asText();

                if ("COMPLETED".equalsIgnoreCase(state)) {
                    logger.info("Backup {} is completed. Exiting loop.", backupId);
                    backupOperation.status = 200;
                    break;
                }
                if ("FAILED".equalsIgnoreCase(state) || "INCOMPLETE".equalsIgnoreCase(state) || "DELETED".equalsIgnoreCase(state)) {
                    backupOperation.status = 500;
                    backupOperation.title = "Zeebe backup " + backupId + " is " + state;
                    backupOperation.message = result.body().path("failureReason").asText(result.raw());
                    operationLog.error(CamundaApplicationInt.COMPONENT.ZEEBE, backupOperation.title + ": " + backupOperation.message);
                    break;
                }
            }
        }
        return backupOperation;
    }

    /**
     * Since Camunda 8.10, the Zeebe backups are on the REST API: GET /v2/backups/runtime (list, sorted by backupId descending)
     * and GET /v2/backups/runtime/{backupId} (status). The state is DOES_NOT_EXIST, IN_PROGRESS, COMPLETED, FAILED,
     * INCOMPLETE or DELETED, and each partition gives createdAt.
     * https://docs.camunda.io/docs/apis-tools/orchestration-cluster-api-rest/orchestration-cluster-api-rest-overview/
     */

    @Override
    public List<BackupInfo> getListBackups() throws OperationException {
        ResponseEntity<BackupInfo> backupStatusResponse = null;
        try {
            logger.debug("Execute [{}]", getUrlListBackup());
            ZeebeRestApi.RestResult result = zeebeRestApi.listRuntimeBackups();
            JsonNode jsonArray = result.body();
            if (!result.isSuccess() || jsonArray == null || !jsonArray.isArray())
                throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "Can't connect",
                        "No answer from [" + getUrlListBackup() + "]: status " + result.status() + " " + result.raw());

            List<BackupInfo> listBackupInfo = new ArrayList<>();
            for (JsonNode t : jsonArray) {
                BackupInfo backupInfo = new BackupInfo();
                backupInfo.backupId = t.path("backupId").asLong();
                backupInfo.components.add(CamundaApplicationInt.COMPONENT.ZEEBE);
                backupInfo.status = BackupInfo.fromZeebeStatus(t.path("state").asText());
                // the date of the first partition which gives it
                for (JsonNode detail : t.path("details")) {
                    String timestamp = detail.path("createdAt").asText("");
                    if (!timestamp.isEmpty()) {
                        backupInfo.backupTime = Instant.parse(timestamp)
                                .atZone(ZoneId.systemDefault()) // Convert to system's time zone
                                .toLocalDateTime();
                        break;
                    }
                }
                listBackupInfo.add(backupInfo);
            }

            logger.info("Execute [{}] found {} backups", getUrlListBackup(), listBackupInfo.size());

            return listBackupInfo;
        } catch (Exception e) {
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "No Connection to Zeebe",
                    "Error url[" + getUrlListBackup() + "] : " + e.getMessage());

        }
    }

    @Override
    public String getUrlListBackup() {
        return zeebeRestApi.getBaseUrl() + "/backups/runtime";
    }

    /**
     * Version of Zeebe (the gateway version given by the topology)
     *
     * @return the version (for example 8.8.3), an empty string if Zeebe is not reachable
     */
    public String getZeebeVersion() {
        try {
            Topology topology = camundaClient.newTopologyRequest().send().join();
            String version = topology.getGatewayVersion();
            return version == null ? "" : version;
        } catch (Exception e) {
            logger.debug("Can't read the Zeebe version via the topology: {}", e.getMessage());
            return "";
        }
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

            logger.info("Execute [{}] broker {} found {} partition", url, brokerId, listPartitionInfo.size());

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
        /**
         * Secondary storage: elasticsearch, opensearch, rdbms, sqlserver... Empty if unknown
         */
        public String secondaryStorage = "";
        /**
         * Where the secondary storage comes from: cluster (detected), configuration (option B) or none
         */
        public String secondaryStorageSource = "";

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
