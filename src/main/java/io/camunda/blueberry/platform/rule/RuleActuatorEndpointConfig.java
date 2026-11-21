package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.platform.rule.RuleActuatorEndpoint.CheckType;
import io.camunda.blueberry.platform.rule.RuleActuatorEndpoint.EndpointCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * The endpoint rule of the orchestration cluster (Zeebe). Since Camunda 8.8, Operate and Tasklist are part of it: they have
 * no actuator of their own. The backup and the restore use the REST API (/v2), not the actuator.
 */
@Configuration
public class RuleActuatorEndpointConfig {

    /**
     * Zeebe, the management port. The checks are the actuator calls Blueberry still makes:
     * <ul>
     *   <li>configprops: read the backup store (type, bucket, base path) of the broker.
     *   Without it, blueberry uses the values of its own configuration (blueberry.container.*)</li>
     *   <li>exporters: read the status of the exporters (enabled or disabled), shown on the dashboard</li>
     *   <li>env: read the secondary storage (camunda.data.secondary-storage.type).
     *   Without it, blueberry uses blueberry.secondaryStorage</li>
     * </ul>
     */
    @Bean
    public Rule ruleZeebeEndpoint(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        return new RuleActuatorEndpoint("Zeebe", blueberryConfig::getZeebeActuatorUrl,
                List.of(new EndpointCheck("configprops", CheckType.ACCESS),
                        new EndpointCheck("exporters", CheckType.ACCESS),
                        new EndpointCheck("env", CheckType.ACCESS)),
                restTemplate);
    }
}
