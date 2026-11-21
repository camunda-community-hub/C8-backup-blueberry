package io.camunda.blueberry.platform.componentconfig;

import io.camunda.blueberry.config.BlueberryConfig;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Build an OrchestrationParameter for the tests, without Spring
 */
public final class TestOrchestration {
    private TestOrchestration() {
    }

    /**
     * @param configuration the keys of blueberry.orchestrationConfig, without the prefix (container.containerType, secondaryStorage...)
     * @param envJson       the answer of Zeebe to /actuator/env. Null: the actuator can't be reached
     */
    public static OrchestrationParameter build(Map<String, String> configuration, String envJson) {
        try {
            BlueberryConfig config = new BlueberryConfig();
            Field url = BlueberryConfig.class.getDeclaredField("zeebeActuatorUrl");
            url.setAccessible(true);
            url.set(config, "http://zeebe:9600");

            RestTemplate restTemplate = new RestTemplate() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> T getForObject(String url, Class<T> responseType, Object... uriVariables) {
                    if (envJson == null) {
                        throw new RestClientException("404");
                    }
                    return (T) envJson;
                }
            };

            Map<String, Object> properties = new HashMap<>();
            configuration.forEach((key, value) -> properties.put(OrchestrationParameter.CONFIG_PREFIX + key, value));
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("test", properties));

            OrchestrationParameter parameters = new OrchestrationParameter(config, new AccessActuator(config, restTemplate), environment);
            parameters.refresh();
            return parameters;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
