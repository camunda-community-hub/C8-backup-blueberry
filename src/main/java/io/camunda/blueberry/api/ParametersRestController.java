package io.camunda.blueberry.api;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OperateConnect;
import io.camunda.blueberry.connect.ZeebeConnect;
import io.camunda.blueberry.platform.rule.AccessParameterValue;
import io.camunda.blueberry.platform.rule.RuleOperateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("blueberry")

public class ParametersRestController {
    Logger logger = LoggerFactory.getLogger(ParametersRestController.class);
    @Autowired
    RuleOperateRepository ruleOperateRepository;
    @Autowired
    private BlueberryConfig blueberryConfig;
    @Autowired
    private ZeebeConnect zeebeConnect;
    @Autowired
    private OperateConnect operateConnect;

    /**
     * Check the system
     * Does Zeebe declare a container? A Type storage?
     * Does Elasticsearch as repository for each component like OperateConnect?
     *
     * @return
     */
    @GetMapping(value = "/api/parameters/getall", produces = "application/json")
    public Map<String, Object> getAll() {
        try {
            logger.debug("Rest [/api/parameters/getall]");
            Map<String, Object> allParameters = new HashMap<>();
            allParameters.putAll(blueberryConfig.getAll());
            allParameters.putAll(zeebeConnect.getParameters());


            AccessParameterValue.ResultParameter resultParameter = ruleOperateRepository.accessParameters();
            allParameters.put("operateSource", resultParameter.accessActuator ? "Operate Actuator" : "Blueberry Configuration");
            allParameters.put("operateRepository", resultParameter.parameters.get("operateRepository"));


            return allParameters;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
