package io.camunda.blueberry.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.camunda.blueberry.connect.*;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.operation.backup.BackupJob;
import io.camunda.blueberry.operation.backup.BackupManager;
import io.camunda.blueberry.platform.ExplorationCluster;
import io.camunda.blueberry.platform.rule.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("blueberry")

public class DashboardRestController {
    public static final String COMPONENT_NOT_CONNECTED = "NOT_CONNECTED";
    public static final String COMPONENT_READY = "READY";
    public static final String COMPONENT_VERIFICATION_IN_PROGRESS = "VERIFICATION_IN_PROGRESS";
    public static final String SCHEDULER_INACTIF = "INACTIF";
    public static final String CLUSTER_ACTIF = "ACTIF";
    public static final String CLUSTER_DISABLED = "DISABLED";
    public static final String CLUSTER_NOT_CONNECTED = "NOT_CONNECTED";

    private final ExplorationCluster explorationCluster;
    private final BackupManager backupManager;

    private final ZeebeConnect zeebeConnect;
    private final ElasticSearchConnect elasticSearchConnect;
    private final OperateConnect operateConnect;
    private final TaskListConnect taskListConnect;
    private final OptimizeConnect optimizeConnect;

    Logger logger = LoggerFactory.getLogger(DashboardRestController.class);

    public DashboardRestController(ExplorationCluster explorationCluster,
                                   BackupManager backupManager,
                                   ZeebeConnect zeebeConnect,
                                   ElasticSearchConnect elasticSearchConnect,
                                   OperateConnect operateConnect,
                                   TaskListConnect taskListConnect,
                                   OptimizeConnect optimizeConnect) {
        this.explorationCluster = explorationCluster;
        this.backupManager = backupManager;
        this.zeebeConnect = zeebeConnect;
        this.elasticSearchConnect = elasticSearchConnect;
        this.operateConnect = operateConnect;
        this.taskListConnect = taskListConnect;
        this.optimizeConnect = optimizeConnect;
    }

    @GetMapping(value = "/api/dashboard/all", produces = "application/json")
    public DashboardStatus all(@RequestParam(name = "forceRefresh", required = false, defaultValue = "false") boolean forceRefresh) {
        try {
            logger.debug("Rest [/api/dashboard/all]");
            DashboardStatus status = new DashboardStatus();

            if (forceRefresh) {
                // Force the refresh
                explorationCluster.refresh();
            }
            ZeebeConnect.ClusterInformation clusterInformation = explorationCluster.getClusterInformation();

            if (clusterInformation != null) {
                status.cluster.partitionsCount = clusterInformation.partitionsCount;
                status.cluster.replicationfactor = clusterInformation.replicationFactor;
                status.cluster.clusterSize = clusterInformation.clusterSize;

            }
            Boolean exporterStatus = explorationCluster.getExporterStatus();
            status.cluster.statusCluster = Boolean.TRUE.equals(exporterStatus) ? CLUSTER_ACTIF : Boolean.FALSE.equals(exporterStatus) ? CLUSTER_DISABLED : CLUSTER_NOT_CONNECTED;

            List<io.camunda.blueberry.connect.BackupInfo> listBackups = explorationCluster.getListBackup();
            if (listBackups != null) {
                status.backup.backupsCount = listBackups.size();
                status.backup.backupsFailed = (int) listBackups.stream().filter(t -> t.status.equals(BackupInfo.Status.FAILED)).count();
                status.backup.backupsComplete = (int) listBackups.stream().filter(t -> t.status.equals(BackupInfo.Status.COMPLETED)).count();
            }

            BackupJob backupJob = backupManager.getBackupJob();
            status.backup.statusBackup = clusterInformation == null ? COMPONENT_NOT_CONNECTED : COMPONENT_READY;
            if (backupJob != null) {
                status.backup.statusBackup = backupJob.getJobStatus().toString();
                OperationLog operationLog = backupJob.getOperationLog();
                if (operationLog != null) {
                }
                status.backup.step = operationLog.getCurrentStep() + "/" + operationLog.getTotalNumberOfSteps() + " (" + operationLog.getOperationName() + ")";
            }


            status.scheduler.statusScheduler = SCHEDULER_INACTIF;
            status.scheduler.cron = "";
            status.scheduler.next = "";
            status.scheduler.delay = "";

            Rule.RuleStatus ruleStatus = explorationCluster.rulesOk();
            status.configuration.statusConfiguration = ruleStatus == null ? "" : ruleStatus.toString();

            return status;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @GetMapping(value = "/api/dashboard/checkConnection", produces = "application/json")
    public Map<String, Object> checkConnection() {
        try {
            logger.debug("checkConnection [/api/dashboard/all]");
            return Map.of("status", "",
                    "zeebe", toMap(zeebeConnect.isConnectedInformation()),
                    "zeebeActuator", toMap(zeebeConnect.isConnectedActuatorInformation()),
                    "elasticsearch", toMap(elasticSearchConnect.isConnectedInformation()),
                    "operate", toMap(operateConnect.isConnectedInformation()),
                    "tasklist", toMap(taskListConnect.isConnectedInformation()),
                    "optimize", toMap(optimizeConnect.isConnectedInformation())
            );
        } catch (Exception e) {
            return Map.of("status", "Error running check connection " + e.getMessage(),
                    "zeebe", Map.of("connection", COMPONENT_VERIFICATION_IN_PROGRESS),
                    "elasticsearch", Map.of("connection", COMPONENT_VERIFICATION_IN_PROGRESS),
                    "operate", Map.of("connection", COMPONENT_VERIFICATION_IN_PROGRESS),
                    "tasklist", Map.of("connection", COMPONENT_VERIFICATION_IN_PROGRESS),
                    "optimize", Map.of("connection", COMPONENT_VERIFICATION_IN_PROGRESS)
            );
        }
    }

    private Map<String, Object> toMap(CamundaApplicationInt.ConnectionInfo connectionInfo) {
        return Map.of("connection", connectionInfo.isConnected ? COMPONENT_READY : COMPONENT_NOT_CONNECTED,
                "explanation", connectionInfo.explanations);
    }

    private class DashboardStatus {
        @JsonProperty
        DashClusterInfo cluster = new DashClusterInfo();
        @JsonProperty
        DashBackupInfo backup = new DashBackupInfo();
        @JsonProperty
        DashSchedulerInfo scheduler = new DashSchedulerInfo();
        @JsonProperty
        DashConfigurationInfo configuration = new DashConfigurationInfo();
    }

    private class DashClusterInfo {
        @JsonProperty
        Integer partitionsCount;
        @JsonProperty
        Integer replicationfactor;
        @JsonProperty
        Integer clusterSize;
        @JsonProperty
        String statusCluster; // "ACTIVE"
    }

    private class DashBackupInfo {
        @JsonProperty
        List<String> history = new ArrayList<>();
        @JsonProperty
        String statusBackup;
        @JsonProperty
        String step;
        @JsonProperty
        Integer backupsCount;
        @JsonProperty
        Integer backupsFailed;
        @JsonProperty
        Integer backupsComplete;
    }

    private class DashSchedulerInfo {
        @JsonProperty
        String statusScheduler; // "INACTIF",
        @JsonProperty
        String cron;
        @JsonProperty
        String next;
        @JsonProperty
        String delay;
    }

    private class DashConfigurationInfo {

        @JsonProperty
        String statusConfiguration;

    }
}