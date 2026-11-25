package io.camunda.blueberry.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.ConnectionInfo;
import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.ZeebeConnect;
import io.camunda.blueberry.connect.cluster.ClusterConnect;
import io.camunda.blueberry.connect.cluster.ClusterFactory;
import io.camunda.blueberry.connect.secondary.ElasticSearchConnect;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.operation.backup.BackupCompleteness;
import io.camunda.blueberry.operation.backup.BackupJob;
import io.camunda.blueberry.operation.backup.BackupManager;
import io.camunda.blueberry.platform.ExplorationCluster;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.rule.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final OptimizeConnect optimizeConnect;
    private final BlueberryConfig blueberryConfig;
    private final SecondaryStorageFactory secondaryStorageFactory;
    private final ClusterFactory clusterFactory;
    private final OrchestrationParameter orchestrationParameter;

    Logger logger = LoggerFactory.getLogger(DashboardRestController.class);

    public DashboardRestController(ExplorationCluster explorationCluster,
                                   BackupManager backupManager,
                                   ZeebeConnect zeebeConnect,
                                   ElasticSearchConnect elasticSearchConnect,
                                   OptimizeConnect optimizeConnect,
                                   BlueberryConfig blueberryConfig,
                                   SecondaryStorageFactory secondaryStorageFactory,
                                   OrchestrationParameter orchestrationParameter,
                                   ClusterFactory clusterFactory) {
        this.clusterFactory = clusterFactory;
        this.orchestrationParameter = orchestrationParameter;
        this.secondaryStorageFactory = secondaryStorageFactory;
        this.explorationCluster = explorationCluster;
        this.backupManager = backupManager;
        this.zeebeConnect = zeebeConnect;
        this.elasticSearchConnect = elasticSearchConnect;
        this.optimizeConnect = optimizeConnect;
        this.blueberryConfig = blueberryConfig;
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
            status.cluster.statusLabel = Boolean.TRUE.equals(exporterStatus)
                    ? "Exporters enabled"
                    : Boolean.FALSE.equals(exporterStatus)
                    ? "Exporters disabled"
                    : "Exporter status unknown";

            try {
                BackupManager.ListBackupResult merged = backupManager.getListBackups();
                List<BackupInfo> listBackups = merged.listBackups;
                status.backup.backupsCount = listBackups.size();
                status.backup.backupsFailed = (int) listBackups.stream()
                        .filter(t -> BackupInfo.Status.FAILED.equals(t.status)).count();
                status.backup.backupsPartial = (int) listBackups.stream()
                        .filter(t -> BackupInfo.Status.PARTIALBACKUP.equals(t.status)).count();
                List<BackupCompleteness.Detail> completeness = backupManager.getBackupCompletenessDetails();
                status.backup.backupsComplete = (int) completeness.stream().filter(BackupCompleteness.Detail::complete).count();
                status.backup.history = DashboardBackupViewHelper.buildHistory(listBackups);
                status.backup.timeline = DashboardBackupViewHelper.buildTimeline(listBackups);
                status.backup.latest = DashboardBackupViewHelper.latestBackupSummary(listBackups);
            } catch (OperationException e) {
                logger.warn("Dashboard backup list unavailable: {}", e.getMessage());
            }

            BackupJob backupJob = backupManager.getBackupJob();
            status.backup.statusBackup = clusterInformation == null ? COMPONENT_NOT_CONNECTED : COMPONENT_READY;
            if (backupJob != null) {
                status.backup.statusBackup = backupJob.getJobStatus().toString();
                OperationLog operationLog = backupJob.getOperationLog();
                if (operationLog != null) {
                    status.backup.step = operationLog.getCurrentStep() + "/" + operationLog.getTotalNumberOfSteps()
                            + " (" + operationLog.getOperationName() + ")";
                }
            }
            if (backupJob != null && backupJob.getJobStatus() == BackupJob.JOBSTATUS.INPROGRESS) {
                status.backup.inProgressBackupId = backupJob.getBackupId();
            }


            status.scheduler.statusScheduler = SCHEDULER_INACTIF;
            status.scheduler.cron = "";
            status.scheduler.next = "";
            status.scheduler.delay = "";

            Rule.RuleStatus ruleStatus = explorationCluster.rulesOk();
            status.configuration.statusConfiguration = ruleStatus == null ? "" : ruleStatus.toString();
            // The Elasticsearch of this card is the one of Optimize: same rule as Optimize
            boolean optimizeActive = optimizeConnect.isActive();
            status.configuration.elasticsearch = optimizeActive ? "enabled" : "disabled";
            status.configuration.secondaryStorage = secondaryStorageFactory.getValueString();
            status.configuration.cluster = clusterFactory.getName();
            status.configuration.backupMode = orchestrationParameter.getBackupMode();
            status.configuration.backupRetention = orchestrationParameter.getParameterString(OrchestrationParameter.BACKUP_RETENTION_WINDOW);
            status.configuration.optimize = optimizeConnect.isActive() ? "enabled" : "disabled";

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
                    "secondaryStorage", pingSecondaryStorage(),
                    "cluster", pingCluster(),
                    "optimize", toMap(optimizeConnect.isConnectedInformation()),
                    "zeebeVersion", zeebeConnect.getZeebeVersion()
            );
        } catch (Exception e) {
            return Map.of("status", "Error running check connection " + e.getMessage(),
                    "zeebeVersion", "",
                    "zeebe", Map.of("connection", COMPONENT_VERIFICATION_IN_PROGRESS),
                    "elasticsearch", Map.of("connection", COMPONENT_VERIFICATION_IN_PROGRESS),
                    "optimize", Map.of("connection", COMPONENT_VERIFICATION_IN_PROGRESS)
            );
        }
    }

    /**
     * CONNECTED, NOT_CONNECTED or NO_IMPLEMENTATION (no storage known, or no ping for it)
     */
    private Map<String, Object> pingSecondaryStorage() {
        SecondaryStorageInt storage = secondaryStorageFactory.getSecondaryStorage();
        SecondaryStorageInt.PingStatus ping = storage == null ? SecondaryStorageInt.PingStatus.NO_IMPLEMENTATION : storage.ping();
        String name = secondaryStorageFactory.getValueString();
        return Map.of("connection", ping.name(),
                "explanation", storage == null ? "No implementation for the secondary storage [" + name + "]" : "Secondary storage [" + storage.getName() + "]");
    }

    /**
     * The cluster of blueberry.cluster.type (kubernetes, docker): READY if its ping() works
     */
    private Map<String, Object> pingCluster() {
        ClusterConnect cluster = clusterFactory.getCluster();
        if (cluster == null) {
            return Map.of("connection", COMPONENT_NOT_CONNECTED,
                    "explanation", "Unknown cluster type [" + clusterFactory.getConfiguredType() + "]. Supported: " + String.join(", ", clusterFactory.getSupportedNames()));
        }
        return Map.of("connection", cluster.ping() ? COMPONENT_READY : COMPONENT_NOT_CONNECTED,
                "explanation", "Cluster [" + cluster.getName() + "]");
    }

    private Map<String, Object> toMap(ConnectionInfo connectionInfo) {
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
        String statusCluster; // ACTIF | DISABLED | NOT_CONNECTED (exporter-derived)
        @JsonProperty
        String statusLabel;
    }

    private class DashBackupInfo {
        @JsonProperty
        List<Map<String, Object>> history = new ArrayList<>();
        /**
         * All the backups, oldest first: the backup time line (one box per backup, per day)
         */
        @JsonProperty
        List<Map<String, Object>> timeline = new ArrayList<>();
        @JsonProperty
        Map<String, Object> latest = new HashMap<>();
        @JsonProperty
        String statusBackup;
        @JsonProperty
        String step;
        @JsonProperty
        Long inProgressBackupId;
        @JsonProperty
        Integer backupsCount;
        @JsonProperty
        Integer backupsFailed;
        @JsonProperty
        Integer backupsComplete;
        @JsonProperty
        Integer backupsPartial;
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

        @JsonProperty
        String elasticsearch; // "enabled" or "disabled", from blueberry.elasticsearch.enabled

        @JsonProperty
        String backupRetention; // retention window of the backups (continuous mode), empty if the cluster does not say

        @JsonProperty
        String cluster; // kubernetes or docker (blueberry.cluster.type)

        @JsonProperty
        String backupMode; // CONTINUOUS, MANUAL, empty if the cluster does not say

        @JsonProperty
        String secondaryStorage; // the secondary storage of the cluster, empty if unknown

        @JsonProperty
        String optimize; // "enabled" or "disabled", from blueberry.optimize.enabled

    }
}