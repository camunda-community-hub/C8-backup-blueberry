package io.camunda.blueberry.config;


import io.camunda.blueberry.access.CamundaApplication;
import io.camunda.blueberry.access.KubernetesAccess;
import io.camunda.blueberry.access.OperationResult;
import io.camunda.blueberry.access.ZeebeAccess;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.platform.PlatformManager;
import io.camunda.blueberry.platform.rule.Rule;
import io.camunda.blueberry.platform.rule.RuleOperateRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The goal of this class is to explore the cluster, and save the information.
 * What is the cluster size/partition/repartition factor? What is the repository component per component?
 * No need to ask this information every time we need it, we can just ask every 5 minutes and store them.
 */
@Component
public class ExplorationCluster {
    Logger logger = LoggerFactory.getLogger(ExplorationCluster.class);


    private final KubernetesAccess kubernetesAccess;

    private final BlueberryConfig blueberryConfig;

    private final PlatformManager platformManager;

    private final RuleOperateRepository ruleOperateRepository;

    private final ZeebeAccess zeebeAccess;

    public ExplorationCluster(KubernetesAccess kubernetesAccess,
                              BlueberryConfig blueberryConfig,
                              PlatformManager platformManager,
                              RuleOperateRepository ruleOperateRepository,
                              ZeebeAccess zeebeAccess) {
        this.kubernetesAccess = kubernetesAccess;
        this.blueberryConfig = blueberryConfig;
        this.platformManager = platformManager;
        this.ruleOperateRepository = ruleOperateRepository;
        this.zeebeAccess = zeebeAccess;
    }

    public void refresh() {
        executeLongExploration();
    }


    @Scheduled(fixedRate = 300000)  // Run every 5 mn
    private void executeShortExploration() {
        refreshExporterStatus();
    }

    @PostConstruct
    public void postConstruct() {
        executeLongExploration();
    }

    private void executeLongExploration() {
        executeShortExploration();
        refreshCheckRules();
        refeshRepositoryComponent();
        refreshClusterInformation();
    }

    /* ******************************************************************** */
    /*                                                                      */
    /*  Exporter status                                                     */
    /*                                                                      */
    /* ******************************************************************** */
    private Boolean exporterStatus;

    private Boolean refreshExporterStatus() {
        try {
            exporterStatus = zeebeAccess.getExporterStatus();
        } catch (OperationException e) {
            exporterStatus = null;
            logger.error(e.getMessage(), e);
        }
        return exporterStatus;
    }

    public Boolean getExporterStatus() {
        return exporterStatus;
    }


    /* ******************************************************************** */
    /*                                                                      */
    /*  ClusterInformation                                                  */
    /*                                                                      */
    /* ******************************************************************** */
    private ZeebeAccess.ClusterInformation clusterInformation;

    private ZeebeAccess.ClusterInformation refreshClusterInformation() {
        clusterInformation = zeebeAccess.getClusterInformation();
        return clusterInformation;
    }

    public ZeebeAccess.ClusterInformation getClusterInformation() {
        return clusterInformation;
    }

    /* ******************************************************************** */
    /*                                                                      */
    /*  Repository per component                                           */
    /*                                                                      */
    /* ******************************************************************** */
    private Map<CamundaApplication.COMPONENT, Object> repositoryPerComponent = new HashMap<>();
    private String namespace = null;

    private Map<CamundaApplication.COMPONENT, Object> refeshRepositoryComponent() {
        // namespace never change, so when we get it, save it
        if (namespace == null) {
            namespace = kubernetesAccess.getCurrentNamespace();
            if (namespace == null) {
                namespace = blueberryConfig.getNamespace();
            }
        }

        kubernetesAccess.connection();
        // Component are present?


        // Components present
        for (CamundaApplication.COMPONENT component : List.of(CamundaApplication.COMPONENT.values())) {
            try {
                // is this component is part of the cluster?

                // Yes, then get the list
                repositoryPerComponent.put(CamundaApplication.COMPONENT.OPERATE, kubernetesAccess.getRepositoryNameV2(CamundaApplication.COMPONENT.OPERATE, namespace));
            } catch (Exception e) {
                logger.error("Can't get result per component {}", e.getMessage());
            }
        }
        return repositoryPerComponent;
    }

    public OperationResult getRepositoryPerComponent() {
        OperationResult operationResult = new OperationResult();
        refresh();
        operationResult.resultMap = repositoryPerComponent.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toString(),  // Convert Component to String
                        Map.Entry::getValue                 // Keep the same value
                ));
        return operationResult;
    }
    /* ******************************************************************** */
    /*                                                                      */
    /*  Rules status                                                        */
    /*                                                                      */
    /*  Keep status of rules check                                          */
    /* ******************************************************************** */

    private Rule.RuleStatus ruleStatus = null;

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
