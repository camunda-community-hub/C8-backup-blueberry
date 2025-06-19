package io.camunda.blueberry.platform.rule;


import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Operate define a repository, and the repository exist in ElasticSearch
 */
@Component
public class RuleOperateRepository implements Rule {

    private final BlueberryConfig blueberryConfig;

    private final KubernetesConnect kubernetesConnect;

    private final ElasticSearchConnect elasticSearchConnect;

    private final OperateConnect operateConnect;

    private final AccessParameterValue accessParameterValue;

    RuleOperateRepository(BlueberryConfig blueberryConfig, KubernetesConnect kubernetesConnect, ElasticSearchConnect elasticSearchConnect,
                          OperateConnect operateConnect, AccessParameterValue accessParameterValue) {
        this.blueberryConfig = blueberryConfig;
        this.kubernetesConnect = kubernetesConnect;
        this.elasticSearchConnect = elasticSearchConnect;
        this.operateConnect = operateConnect;
        this.accessParameterValue = accessParameterValue;
    }

    @Override
    public boolean validRule() {
        // is Operate is define in the cluster?
        return blueberryConfig.getOperateActuatorUrl() != null;
    }

    @Override
    public String getName() {
        return "Operate Repository";
    }

    public String getExplanations() {
        return "Operate must define a repository name. Elasticsearch must define this repository, and map it to a valid container.";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of("https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/operate-tasklist-backup/",
                "https://github.com/camunda-community-hub/C8-backup-toolkit/blob/main/README.md");
    }

    @Override
    public RuleInfo check() {
        return operation(false);
    }

    @Override
    public RuleInfo configure() {
        return operation(true);
    }

    private RuleInfo operation(boolean execute) {
        boolean accessPodRepository = false;
        boolean accessElasticsearchRepository = false;
        boolean createElasticsearchRepository = false;


        // get the Pod description
        RuleInfo ruleInfo = new RuleInfo(this);


        if (!validRule()) {
            return ruleInfo;
        }
        // ---------- First step, ask Operate for the name of the repository
        // the rule is in progress
        ruleInfo.setStatus(RuleStatus.INPROGRESS);

        AccessParameterValue.ResultParameter resultParameter = accessParameters();
        ruleInfo.addDetails(resultParameter.accessActuator ? "Access RepositoryName exploring Operate /actuator/env" : "Access RepositoryName exploring Blueberry configuration");

        String operateRepository = (String) resultParameter.parameters.get("operateRepository");

        if (operateRepository == null) {
            ruleInfo.setStatus(RuleStatus.FAILED);
        }

        ruleInfo.addVerificationsAssertBoolean("Access pod repository, retrieve [" + operateRepository + "]", operateRepository != null,
                "From Configuration");

        //------------ Second step, verify if the repository exists in elasticSearch
        if (ruleInfo.inProgress()) {
            // now check if the repository exists in Elastic search
            OperationResult operationResult = elasticSearchConnect.existRepository(operateRepository);
            accessElasticsearchRepository = operationResult.resultBoolean;
            ruleInfo.addVerificationsButWillBeFixed("Check Elasticsearch repository [" + operateRepository + "] :"
                            + operationResult.details,
                    accessElasticsearchRepository ? RuleStatus.CORRECT : RuleStatus.FAILED,
                    operationResult.command);

            // if the repository exists, then we stop the rule execution here
            if (accessElasticsearchRepository) {
                ruleInfo.addDetails("Repository exists in Elastic search");
            } else {
                // if we don't execute the rule, we stop here on a failure
                if (!execute) {
                    ruleInfo.addDetails("Repository does not exist in Elastic search, and must be created");
                    ruleInfo.setStatus(RuleStatus.FAILED);
                }
            }
        }


        // Third step, create the repository if asked
        if (execute && ruleInfo.inProgress()) {
            OperationResult operationResult = elasticSearchConnect.createRepository(operateRepository,
                    blueberryConfig.getContainerType(),
                    blueberryConfig.getOperateContainerBasePath());
            if (operationResult.success) {
                ruleInfo.addDetails("Repository is created in ElasticSearch");
            } else {
                ruleInfo.addError("Error when creating the repository in ElasticSearch :" + operationResult.details);
                ruleInfo.setStatus(RuleStatus.FAILED);
            }
            ruleInfo.addVerifications("Check Elasticsearch repository [" + operateRepository
                            + "] ContainerType[" + blueberryConfig.getContainerType()
                            + "] ContainerName[" + blueberryConfig.getAzureContainerName()
                            + "] basePath[" + blueberryConfig.getOperateContainerBasePath() + "]",
                    operationResult.success ? RuleStatus.CORRECT : RuleStatus.FAILED,
                    operationResult.command);
        }
        // Still in progress at this point? All is OK then
        if (ruleInfo.inProgress()) {
            ruleInfo.setStatus(RuleStatus.CORRECT);
        }
        return ruleInfo;
    }


    public AccessParameterValue.ResultParameter accessParameters() {
        return accessParameterValue.accessParameterViaActuator(CamundaApplicationInt.COMPONENT.OPERATE, List.of("operateRepository"), blueberryConfig.getOperateActuatorUrl() + "/actuator/env");
    }


    private String getRepositoryByConfiguration(RuleInfo ruleInfo) {
        ruleInfo.addDetails("Access RepositoryName from Blueberry configuration");
        return blueberryConfig.getOperateRepository();
    }

    private String getRepositoryByOperateEnvironment(RuleInfo ruleInfo) {
        ruleInfo.addDetails("Access RepositoryName exploring Operate environment");


        return operateConnect.getBackupRepositoryName();
    }

    private String getRepositoryKubernetes(RuleInfo ruleInfo) {
        OperationResult operationResult = kubernetesConnect.getRepositoryName(CamundaApplicationInt.COMPONENT.OPERATE, blueberryConfig.getNamespace());
        if (!operationResult.success) {
            ruleInfo.addDetails("Can't access the Repository name in the pod, or does not exist");
            ruleInfo.addDetails(operationResult.details);
            ruleInfo.setStatus(RuleStatus.FAILED);
        } else {
            ruleInfo.addDetails("Access RepositoryName exploring Kubernetes environment");
            return operationResult.resultSt;
        }
        return null;
    }

}
