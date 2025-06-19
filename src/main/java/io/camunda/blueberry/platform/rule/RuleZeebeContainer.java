package io.camunda.blueberry.platform.rule;


import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Operate define a repository, and the repository exist in ElasticSearch
 */
@Component
public class RuleZeebeContainer implements Rule {
    public static final List<String> PARAMETERS_KEYS = List.of(
            "container.containerType",
            "backupStore",
            "container.azure.connectionString"
    );
    private final BlueberryConfig blueberryConfig;
    private final AccessParameterValue accessParameterValue;

    RuleZeebeContainer(BlueberryConfig blueberryConfig,
                       AccessParameterValue accessParameterValue) {
        this.blueberryConfig = blueberryConfig;
        this.accessParameterValue = accessParameterValue;
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

            AccessParameterValue.ResultParameter resultParameter = accessParameters();
            for (String parameterKey : PARAMETERS_KEYS) {
                ruleInfo.addDetails(resultParameter.accessActuator ? "Access key [" + parameterKey + "] exploring Zeebe /actuator/env" : "Access [" + parameterKey + "] exploring Blueberry configuration");

                String value = (String) resultParameter.parameters.get(parameterKey);

                if (value == null) {
                    ruleInfo.setStatus(RuleStatus.FAILED);
                }
            }

            // According to the type of storage
            List<String> additionalParametersToCheck = null;
            if ("AZURE".equals(resultParameter.parameters.get("container"))) {
                additionalParametersToCheck = List.of("ZEEBE_BROKER_DATA_BACKUP_AZURE_CONNECTIONSTRING", "ZEEBE_BROKER_DATA_BACKUP_AZURE_BASEPATH");
            }


            if (additionalParametersToCheck != null) {
                AccessParameterValue.ResultParameter additionalParameter = accessParameterValue.accessParameterViaActuator(CamundaApplicationInt.COMPONENT.ZEEBE,
                        additionalParametersToCheck,
                        blueberryConfig.getZeebeActuatorUrl() + "/actuator/env");
                for (String parameterKey : additionalParametersToCheck) {
                    if (!additionalParameter.parameters.containsKey(parameterKey)) {
                        ruleInfo.addError("Missing parameter [" + parameterKey + "]");
                        ruleInfo.setStatus(RuleStatus.FAILED);
                    }
                }


            }
        } else
            ruleInfo.setStatus(RuleStatus.DEACTIVATED);
        return ruleInfo;
    }

    public AccessParameterValue.ResultParameter accessParameters() {
        return accessParameterValue.accessParameterViaActuator(CamundaApplicationInt.COMPONENT.ZEEBE, List.of("container.containerType"), blueberryConfig.getOperateActuatorUrl() + "/actuator/env");
    }
}
