package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.toolbox.WebActuator;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientConfiguration;
import io.camunda.zeebe.client.api.response.Topology;
import io.camunda.zeebe.spring.client.configuration.ZeebeClientConfigurationImpl;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

@Component
public class ZeebeConnect extends WebActuator implements BackupComponentInt {
    private final ObjectMapper objectMapper;
    private final ZeebeClientConfigurationImpl zeebeClientConfiguration;
    private final BlueberryConfig blueberryConfig;
    private final RestTemplate restTemplate;
    private final WebActuator webActuator;
    Logger logger = LoggerFactory.getLogger(ZeebeConnect.class);
    private ZeebeClient myZeebeClient;
    private long lastTentativeConnection = 0;

    public ZeebeConnect(BlueberryConfig blueberryConfig, RestTemplate restTemplate, ObjectMapper objectMapper, ZeebeClientConfigurationImpl zeebeClientConfiguration) {
        super(restTemplate);
        webActuator = new WebActuator(restTemplate);
        this.blueberryConfig = blueberryConfig;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.zeebeClientConfiguration = zeebeClientConfiguration;
    }

    public boolean connection() {
        boolean isConnected = isConnected();
        logger.info("Connection: already connected {} connect to Grpc[{}}]", isConnected, zeebeClientConfiguration.getGrpcAddress());

        if (isConnected)
            return true;

        // Update the last connection
        lastTentativeConnection = System.currentTimeMillis();

        try {
            this.myZeebeClient = ZeebeClient.newClient(zeebeClientConfiguration);
            final Topology topology = myZeebeClient.newTopologyRequest().send().join();
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
     * @return
     */
    public boolean isConnected() {

        try {
            final Topology topology = myZeebeClient.newTopologyRequest().send().join();
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
     * @return
     */
    public CamundaApplicationInt.ConnectionInfo isConnectedInformation() {
        return new CamundaApplicationInt.ConnectionInfo(isConnected(), "Grpc Connection[" + zeebeClientConfiguration.getGrpcAddress() + "]");
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

    public ClusterInformation getClusterInformation() {
        try {
            final Topology topology = myZeebeClient.newTopologyRequest().send().join();
            ClusterInformation clusterInformation = new ClusterInformation();
            clusterInformation.clusterSize = topology.getClusterSize();
            clusterInformation.partitionsCount = topology.getPartitionsCount();
            clusterInformation.replicationFactor = topology.getReplicationFactor();

            return clusterInformation;
        } catch (Exception e) {
            logger.error("Can't get ClusterInformation via newTopologyRequest", e);
            return null;
        }
    }

    public ZeebeClientConfiguration getZeebeConfiguration() {
        return zeebeClientConfiguration;
    }

    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("zeebeIsConnected", isConnected());
        for (Method method : zeebeClientConfiguration.getClass().getMethods()) {
            if (method.getName().startsWith("get") &&
                    method.getParameterCount() == 0 &&
                    !method.getReturnType().equals(void.class)) {
                String propertyName = lowerCaseFirstLetter(method.getName().substring(3));
                try {
                    Object value = method.invoke(zeebeClientConfiguration);
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

        Map<String, Object> backupBody = Map.of("backupId", backupId);
        ResponseEntity<String> backupResponse = restTemplate.postForEntity(blueberryConfig.getZeebeActuatorUrl() + "/actuator/backups", backupBody, String.class);

        logger.info("Backup status response for url {}: {}, {}", blueberryConfig.getZeebeActuatorUrl(), backupResponse.getStatusCodeValue(), backupResponse.getBody());
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
                    blueberryConfig.getZeebeActuatorUrl(), response.getStatusCodeValue(), response.getBody());

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

    /* ******************************************************************** */
    /*                                                                      */
    /*  getInformation                                                      */
    /*                                                                      */
    /* ******************************************************************** */
    public class ClusterInformation {
        public int clusterSize;
        public int partitionsCount;
        public int replicationFactor;
    }


}
