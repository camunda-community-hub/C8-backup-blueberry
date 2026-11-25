package io.camunda.blueberry.platform.componentconfig;

import io.camunda.blueberry.config.BlueberryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrchestrationParameterTest {

    private static final Map<String, Object> CONFIG = Map.of(
            "blueberry.orchestrationConfig.container.containerType", "gcs",
            "blueberry.orchestrationConfig.backupSchedule", "PT10M",
            "blueberry.orchestrationConfig.backupRetentionWindow", "P7D");

    private static OrchestrationParameter build(AccessActuator.Result actuatorAnswer) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", CONFIG));
        AccessActuator actuator = new AccessActuator(new BlueberryConfig(), null) {
            @Override
            public Result readEnvironment(Map<String, List<String>> wanted) {
                return actuatorAnswer;
            }
        };
        OrchestrationParameter parameters = new OrchestrationParameter(new BlueberryConfig(), actuator, environment);
        parameters.refresh();
        return parameters;
    }

    @Test
    void actuatorFirstThenConfiguration() {
        OrchestrationParameter parameters = build(new AccessActuator.Result(true, false,
                Map.of("container.containerType", "FILESYSTEM", "backupContinuous", "true"), ""));

        assertEquals("FILESYSTEM", parameters.getParameterValue("container.containerType"));
        assertEquals("true", parameters.getParameterValue("backupContinuous"));
        // not given by the actuator: the application.yaml
        assertEquals("PT10M", parameters.getParameterValue("backupSchedule"));
        // given by nobody
        assertNull(parameters.getParameterValue("backupCheckpointInterval"));

        Parameter store = parameters.getParameters().stream()
                .filter(p -> p.getName().equals("container.containerType")).findFirst().orElseThrow();
        assertEquals(Parameter.Origin.ACTUATOR, store.getOrigin());
        assertEquals(Parameter.Origin.CONFIG, parameters.getParameters().stream()
                .filter(p -> p.getName().equals("backupSchedule")).findFirst().orElseThrow().getOrigin());
        // one parameter for each variable searched
        assertEquals(OrchestrationParameter.ENVIRONMENT.size(), parameters.getParameters().size());
        assertNull(parameters.getParameterValue("unknown"));
    }

    @Test
    void actuatorNotReachableUsesTheConfiguration() {
        OrchestrationParameter parameters = build(new AccessActuator.Result(false, false, Map.of(), "not exposed"));

        assertEquals("gcs", parameters.getParameterValue("container.containerType"));
        assertEquals("P7D", parameters.getParameterValue(OrchestrationParameter.BACKUP_RETENTION_WINDOW));
        parameters.getParameters().forEach(p -> assertEquals(Parameter.Origin.CONFIG, p.getOrigin()));
    }

    @Test
    void theKindOfRdbmsComesFromTheJdbcUrl() {
        assertEquals("postgres", kind("rdbms", "jdbc:postgresql://db:5432/camunda"));
        assertEquals("sqlserver", kind("RDBMS", "jdbc:sqlserver://db:1433;databaseName=camunda"));
        assertEquals("h2", kind("rdbms", "jdbc:h2:file:./camunda-data/h2db"));
        assertEquals("mariadb", kind("rdbms", "jdbc:mariadb://db/camunda"));
        // no url: unknown
        assertEquals("", kind("rdbms", ""));
        // not rdbms: the value itself
        assertEquals("elasticsearch", kind("elasticsearch", ""));
    }

    private static String kind(String type, String url) {
        String env = "{\"propertySources\":[{\"name\":\"systemEnvironment\",\"properties\":{"
                + "\"CAMUNDA_DATA_SECONDARYSTORAGE_TYPE\":{\"value\":\"" + type + "\"}"
                + (url.isEmpty() ? "" : ",\"CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL\":{\"value\":\"" + url + "\"}")
                + "}}]}";
        return TestOrchestration.build(Map.of(), env).getSecondaryStorageKind();
    }
}

