package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.Parameter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Zeebe record repository is defined in ElasticSearch
 */
@Component
public class RuleZeebeContainer implements Rule {
    private final BlueberryConfig blueberryConfig;
    private final OrchestrationParameter orchestrationParameter;

    RuleZeebeContainer(BlueberryConfig blueberryConfig,
                       OrchestrationParameter orchestrationParameter) {
        this.blueberryConfig = blueberryConfig;
        this.orchestrationParameter = orchestrationParameter;
    }

    @Override
    public boolean validRule() {

        return blueberryConfig.getZeebeActuatorUrl() != null;
    }

    @Override
    public String getName() {
        return "Zeebe Container";
    }

    public String getExplanations() {
        return "Zeebe must define a container to backup the data.";
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

        // get the Pod description
        RuleInfo ruleInfo = new RuleInfo(this);
        if (validRule()) {

            OrchestrationParameter.ResultParameter resultParameter;

            resultParameter = orchestrationParameter.accessParameters();
            // Where the values come from: the environment of Zeebe (CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_*), or the configuration
            if (resultParameter.info != null && !resultParameter.info.isBlank()) {
                ruleInfo.addDetails(resultParameter.info);
            }
            String origin = resultParameter.accessActuator ? "actuator" : "config";
            ruleInfo.addVerificationsAssertBoolean("Container type defined: [" + resultParameter.parameters.get(OrchestrationParameter.CONTAINER_CONTAINERTYPE.name()) + "] (" + origin + ")",
                    resultParameter.parameters.containsKey(OrchestrationParameter.CONTAINER_CONTAINERTYPE.name()),
                    null);

            if (!resultParameter.parameters.containsKey(OrchestrationParameter.CONTAINER_CONTAINERTYPE.name())) {
                ruleInfo.addError("Value for parameter [" + OrchestrationParameter.CONTAINER_CONTAINERTYPE + "] is required. Zeebe: "
                        + "CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_STORE, readable by Blueberry in /actuator/env of Zeebe (see the details)");
                ruleInfo.setStatus(RuleStatus.FAILED);
            }

            // According to the type of storage, different check
            List<String> additionalParametersToCheck = null;
            String containerTypeValue = String.valueOf(resultParameter.parameters.get(OrchestrationParameter.CONTAINER_CONTAINERTYPE.name()));

            // Elasticsearch and Zeebe use the same container: the type used to create the repository (the parameter
            // container.containerType: the actuator, else blueberry.orchestrationConfig) must be the one of Zeebe
            String blueberryType = orchestrationParameter.getParameterString(OrchestrationParameter.CONTAINER_CONTAINERTYPE);
            if (resultParameter.parameters.containsKey(OrchestrationParameter.CONTAINER_CONTAINERTYPE.name()) && !blueberryType.isBlank()) {
                boolean sameType = blueberryType.equalsIgnoreCase(containerTypeValue);
                ruleInfo.addVerificationsAssertBoolean("Container type of Zeebe [" + containerTypeValue + "] (" + origin
                                + ") is the container type used by Blueberry for Elasticsearch [" + blueberryType + "] (" + originOf(OrchestrationParameter.CONTAINER_CONTAINERTYPE.name()) + ")",
                        sameType, null);
                if (!sameType) {
                    ruleInfo.addError("Zeebe backs up in [" + containerTypeValue + "] but Blueberry uses ["
                            + blueberryType + "] (blueberry.orchestrationConfig.container.containerType): Elasticsearch and Zeebe must use the same container");
                    ruleInfo.setStatus(RuleStatus.FAILED);
                }
            }

            // Filesystem: Elasticsearch creates its repository in blueberry.orchestrationConfig.filesystem.path
            if (OrchestrationParameter.STORE_FILESYSTEM.equalsIgnoreCase(containerTypeValue)) {
                additionalParametersToCheck = List.of(OrchestrationParameter.CONTAINER_BASEPATH);
                String elasticsearchPath = orchestrationParameter.getParameterString(OrchestrationParameter.FILESYSTEM_PATH);
                ruleInfo.addVerificationsAssertBoolean("Directory of the Elasticsearch repository (blueberry.orchestrationConfig.filesystem.path): ["
                                + elasticsearchPath + "] (" + originOf(OrchestrationParameter.FILESYSTEM_PATH.name()) + ")",
                        !elasticsearchPath.isEmpty(), null);
                if (elasticsearchPath.isEmpty()) {
                    ruleInfo.addError("blueberry.orchestrationConfig.filesystem.path is required: the directory of the Elasticsearch repository "
                            + "(Elasticsearch path.repo must contain it)");
                    ruleInfo.setStatus(RuleStatus.FAILED);
                }
            }
            if (OrchestrationParameter.STORE_AZURE.equalsIgnoreCase(containerTypeValue)) {
                additionalParametersToCheck = List.of(OrchestrationParameter.AZURE_CONNECTIONSTRING, OrchestrationParameter.CONTAINER_BASEPATH, OrchestrationParameter.CONTAINER_BUCKETNAME);
            }
            if (OrchestrationParameter.STORE_GCS.equalsIgnoreCase(containerTypeValue)) {
                additionalParametersToCheck = List.of(OrchestrationParameter.CONTAINER_BASEPATH, OrchestrationParameter.CONTAINER_BUCKETNAME);
            }
            if (OrchestrationParameter.STORE_S3.equalsIgnoreCase(containerTypeValue)) {
                additionalParametersToCheck = List.of(OrchestrationParameter.CONTAINER_BASEPATH, OrchestrationParameter.CONTAINER_BUCKETNAME);
            }

            if (additionalParametersToCheck != null) {

                for (String parameterKey : additionalParametersToCheck) {
                    ruleInfo.addVerificationsAssertBoolean("Parameter [" + parameterKey + "]"
                                    + (resultParameter.parameters.containsKey(parameterKey)
                                    ? ": [" + resultParameter.parameters.get(parameterKey) + "] (" + origin + ")" : ""),
                            resultParameter.parameters.containsKey(parameterKey),
                            null);

                    if (!resultParameter.parameters.containsKey(parameterKey)) {
                        ruleInfo.addError("Missing parameter [" + parameterKey + "]");
                        ruleInfo.setStatus(RuleStatus.FAILED);
                    }
                }


            }
        } else
            ruleInfo.setStatus(RuleStatus.DEACTIVATED);

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

