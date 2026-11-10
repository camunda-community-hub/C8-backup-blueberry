package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.platform.rule.RuleActuatorEndpoint.CheckType;
import io.camunda.blueberry.platform.rule.RuleActuatorEndpoint.EndpointCheck;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * One endpoint rule per component: Operate, Tasklist and Zeebe.
 */
@Configuration
public class RuleActuatorEndpointConfig {

    @Bean
    public Rule ruleOperateEndpoint(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        return new RuleActuatorEndpoint("Operate", blueberryConfig::getOperateActuatorUrl,
                List.of(new EndpointCheck("backups", CheckType.ACCESS),
                        // read the name of the backup repository. Without it, blueberry uses blueberry.operateRepository
                        new EndpointCheck("env", CheckType.ACCESS)),
                restTemplate);
    }

    @Bean
    public Rule ruleTasklistEndpoint(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        return new RuleActuatorEndpoint("Tasklist", blueberryConfig::getTasklistActuatorUrl,
                List.of(new EndpointCheck("backups", CheckType.ACCESS),
                        // read the name of the backup repository. Without it, blueberry uses blueberry.tasklistRepository
                        new EndpointCheck("env", CheckType.ACCESS)),
                restTemplate);
    }

    /**
     * Zeebe:
     * <ul>
     *   <li>backups: list and start a backup</li>
     *   <li>exporting: pause and resume the exporters during a backup. Checked in the /actuator list:
     *   calling /actuator/exporting/pause would pause the exporters</li>
     *   <li>configprops: read the backup store (type, bucket, base path) of the broker.
     *   Without it, blueberry uses the values of its own configuration (blueberry.container.*)</li>
     *   <li>exporters: read the status of the exporters (enabled or disabled), shown on the dashboard</li>
     * </ul>
     */
    @Bean
    public Rule ruleZeebeEndpoint(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        return new RuleActuatorEndpoint("Zeebe", blueberryConfig::getZeebeActuatorUrl,
                List.of(new EndpointCheck("backups", CheckType.ACCESS),
                        new EndpointCheck("exporting", CheckType.LINK),
                        new EndpointCheck("configprops", CheckType.ACCESS),
                        new EndpointCheck("exporters", CheckType.ACCESS)),
                restTemplate);
    }
}
