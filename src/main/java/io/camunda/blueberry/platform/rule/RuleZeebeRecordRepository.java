package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OperationResult;
import io.camunda.blueberry.connect.secondary.ElasticSearchConnect;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.Parameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Zeebe record repository is defined in ElasticSearch
 */
@Component
public class RuleZeebeRecordRepository implements Rule {


    @Autowired
    BlueberryConfig blueberryConfig;

    @Autowired
    ElasticSearchConnect elasticSearchConnect;

    @Autowired
    OrchestrationParameter orchestrationParameter;

    @Autowired
    io.camunda.blueberry.connect.secondary.ElasticsearchRepositories repositories;

    @Override
    public boolean validRule() {
        // is a repository is define in the cluster?
        return true;
    }

    @Override
    public String getName() {
        return "Zeebe Record Repository";
    }

    public String getExplanations() {
        return "The Zeebe records are backed up directly by Elasticsearch: a repository must exist in Elasticsearch (mapped to a valid container), and its name comes from Blueberry. Blueberry then sends a request to Elasticsearch to snapshot the zeebe-record indices into that repository.";
    }


    @Override
    public List<String> getUrlDocumentation() {
        return List.of();
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

        if (validRule()) {

            //------------ Second step, verify if the repository exists in elasticSearch
            if (ruleInfo.inProgress()) {
                // now check if the repository exists in Elastic search
                OperationResult operationResult = elasticSearchConnect.existRepository(repositories.zeebeRecord().getValueString());
                accessElasticsearchRepository = operationResult.resultBoolean;
                ruleInfo.addVerifications("Check Elasticsearch repository [" + repositories.zeebeRecord().getValueString() + "] parameter " + repositories.zeebeRecord().describe() + " :"
                                + operationResult.details,
                        accessElasticsearchRepository ? RuleStatus.CORRECT : execute ? RuleStatus.FAILEDBUTWILLBEFIXED : RuleStatus.FAILED,
                        operationResult.command);

                // if the repository exists, then we stop the rule execution here
                if (accessElasticsearchRepository) {
                    ruleInfo.addDetails("ZeebeRecord is backed up directly by Elasticsearch. A repository must exist in Elasticsearch, and its name comes from Blueberry: ["
                            + repositories.zeebeRecord().getValueString() + "] parameter " + repositories.zeebeRecord().describe()
                            + ". Blueberry sends a request to Elasticsearch to snapshot the zeebe-record indices into that repository.");
                } else {
                    // if we don't execute the rule, we stop here on a failure
                    if (!execute) {
                        ruleInfo.addError("ZeebeRecord is backed up directly by Elasticsearch, into the repository [" + repositories.zeebeRecord().getValueString()
                                + "] parameter " + repositories.zeebeRecord().describe() + ". This repository does not exist in Elasticsearch and must be created: use Configure");
                        ruleInfo.setStatus(RuleStatus.FAILED);
                    }
                }
            }


            // Third step, create the repository if asked
            if (execute && ruleInfo.inProgress()) {
                OperationResult operationResult = elasticSearchConnect.createRepository(repositories.zeebeRecord().getValueString(),
                        orchestrationParameter.getParameterString(OrchestrationParameter.CONTAINER_CONTAINERTYPE),
                        blueberryConfig.getZeebeRecordContainerBasePath());
                if (operationResult.success) {
                    ruleInfo.addActivity("Repository is created in ElasticSearch", RuleStatus.DONEWITHSUCCESS, operationResult.command);
                } else {
                    ruleInfo.addError("Error when creating the repository in ElasticSearch :" + operationResult.details);
                    ruleInfo.setStatus(RuleStatus.FAILED);
                }
                ruleInfo.addVerifications("Check Elasticsearch repository [" + repositories.zeebeRecord().getValueString()
                                + "] parameter " + repositories.zeebeRecord().describe() + " ContainerType[" + orchestrationParameter.getParameterString(OrchestrationParameter.CONTAINER_CONTAINERTYPE)
                                + "] (" + originOf(OrchestrationParameter.CONTAINER_CONTAINERTYPE.name())
                                + ") basePath[" + blueberryConfig.getZeebeRecordContainerBasePath() + "] (config)",
                        operationResult.success ? RuleStatus.CORRECT : RuleStatus.FAILED,
                        operationResult.command);

            }
        }
        if (ruleInfo.inProgress()) {
            ruleInfo.setStatus(RuleStatus.CORRECT);
        }
        return ruleInfo;
    }

    /**
     * @return where the value of the parameter comes from: actuator or config
     */
    private String originOf(String parameterName) {
        Parameter parameter = orchestrationParameter.getParameter(parameterName);
        return parameter == null ? "config" : parameter.getOriginLabel();
    }
}

