package io.camunda.blueberry.platform;


import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.ZeebeConnect;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.platform.rule.Rule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The goal of this class is to explore the cluster, and save the information.
 * What is the cluster size/partition/repartition factor? What is the status of the exporters and of the rules?
 * No need to ask this information every time we need it, we can just ask every 5 minutes and store them.
 */
@Component
public class ExplorationCluster {
    private final PlatformManager platformManager;
    private final ZeebeConnect zeebeConnect;
    Logger logger = LoggerFactory.getLogger(ExplorationCluster.class);
    /* ******************************************************************** */
    /*                                                                      */
    /*  Exporter status                                                     */
    /*                                                                      */
    /* ******************************************************************** */
    private Boolean exporterStatus;
    /* ******************************************************************** */
    /*                                                                      */
    /*  backup list                                                         */
    /*                                                                      */
    /* ******************************************************************** */
    private List<BackupInfo> listBackups;
    /* ******************************************************************** */
    /*                                                                      */
    /*  ClusterInformation                                                  */
    /*                                                                      */
    /* ******************************************************************** */
    private ZeebeConnect.ClusterInformation clusterInformation;
    private Rule.RuleStatus ruleStatus = null;

    public ExplorationCluster(PlatformManager platformManager,
                              ZeebeConnect zeebeConnect) {
        this.platformManager = platformManager;
        this.zeebeConnect = zeebeConnect;
    }

    public void refresh() {
        executeLongExploration();
    }

    @Scheduled(fixedRate = 300000)  // Run every 5 mn
    private void executeShortExploration() {
        refreshExporterStatus();
        refreshListBackup();
    }

    @PostConstruct
    public void postConstruct() {
        logger.info("Starting exploration cluster in background");
        // Run this in a different thread, because if it failed, the initialization stops
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.submit(() -> initialStartup());

        executor.shutdown(); // Optional: stop the executor after task
    }

    private void initialStartup() {
        logger.info("ExplorationCluster: start the application ");
        zeebeConnect.connection();

        executeLongExploration();
    }

    private void executeLongExploration() {
        executeShortExploration();
        refreshCheckRules();
        refreshClusterInformation();
    }

    private Boolean refreshExporterStatus() {
        try {
            exporterStatus = zeebeConnect.getExporterStatus();
        } catch (OperationException e) {
            exporterStatus = null;
            logger.error(e.getMessage(), e);
        }
        return exporterStatus;
    }

    public Boolean getExporterStatus() {
        return exporterStatus;
    }

    private List<BackupInfo> refreshListBackup() {
        try {
            listBackups = zeebeConnect.getListBackups();
        } catch (OperationException e) {
            listBackups = null;
            logger.error(e.getMessage(), e);
        }
        return listBackups;
    }

    public List<BackupInfo> getListBackup() {
        return listBackups;
    }

    private ZeebeConnect.ClusterInformation refreshClusterInformation() {
        clusterInformation = zeebeConnect.getClusterInformation();
        return clusterInformation;
    }

    public ZeebeConnect.ClusterInformation getClusterInformation() {
        return clusterInformation;
    }

    /* ******************************************************************** */
    /*                                                                      */
    /*  Rules status                                                        */
    /*                                                                      */
    /*  Keep status of rules check                                          */
    /* ******************************************************************** */

    /**
     * Rules are valid?
     *
     * @return null if no check was performed, RuleStatus else
     */
    public Rule.RuleStatus rulesOk() {
        if (ruleStatus == null) {
            ruleStatus = refreshCheckRules();
        }
        return ruleStatus;
    }

    private Rule.RuleStatus refreshCheckRules() {
        try {
            List<Rule.RuleInfo> listRules = platformManager.checkAllRules();
            // DEACTIVATE => INPROGRESS => CORRECT => FAILED
            ruleStatus = Rule.RuleStatus.DEACTIVATED;
            for (Rule.RuleInfo ruleInfo : listRules) {
                if (ruleInfo.getStatus() == Rule.RuleStatus.FAILED)
                    ruleStatus = Rule.RuleStatus.FAILED;
                if (ruleInfo.getStatus() == Rule.RuleStatus.INPROGRESS && ruleStatus == Rule.RuleStatus.DEACTIVATED) {
                    ruleStatus = Rule.RuleStatus.INPROGRESS;
                }
                if (ruleInfo.getStatus() == Rule.RuleStatus.CORRECT
                        && (ruleStatus == Rule.RuleStatus.INPROGRESS || ruleStatus == Rule.RuleStatus.DEACTIVATED)) {
                    ruleStatus = Rule.RuleStatus.CORRECT;
                }
                // Deactivated : don't care
            }
            return ruleStatus;
        } catch (OperationException e) {
            logger.error("Can't get rules for {}", e.getMessage());
            return null;
        }

    }
}
