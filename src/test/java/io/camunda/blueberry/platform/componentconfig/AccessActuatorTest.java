package io.camunda.blueberry.platform.componentconfig;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessActuatorTest {

    /**
     * The answer of /actuator/env of the orchestration of the docker compose (variables of the container)
     */
    private static final String ENV = """
            {"activeProfiles":[],"propertySources":[
              {"name":"systemProperties","properties":{"java.version":{"value":"21"}}},
              {"name":"systemEnvironment","properties":{
                "CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_STORE":{"value":"FILESYSTEM","origin":"System Environment Property"},
                "CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_FILESYSTEM_BASEPATH":{"value":"/usr/local/camunda/backups/zeebe"}}}
            ]}
            """;

    private static final String MASKED = """
            {"propertySources":[{"name":"systemEnvironment","properties":{
                "CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_STORE":{"value":"******"},
                "CAMUNDA_DATA_PRIMARYSTORAGE_BACKUP_FILESYSTEM_BASEPATH":{"value":"******"}}}]}
            """;

    @Test
    void theBackupStoreAndTheBasePathAreReadInTheEnvironment() {
        AccessActuator.Result result = AccessActuator.parse(ENV, OrchestrationParameter.ENVIRONMENT);

        assertTrue(result.reachable());
        assertFalse(result.masked());
        assertEquals("FILESYSTEM", result.values().get(OrchestrationParameter.CONTAINER_CONTAINERTYPE));
        assertEquals("/usr/local/camunda/backups/zeebe", result.values().get("filesystem.basePath"));
        // not defined: not in the result
        assertNull(result.values().get("gcs.bucketName"));
    }

    @Test
    void theFilesystemParametersOfTheRule() {
        AccessActuator.Result result = AccessActuator.parse(ENV, OrchestrationParameter.ENVIRONMENT);

        OrchestrationParameter.ResultParameter parameters = OrchestrationParameter.fullFillFromEnvironment(new OrchestrationParameter.ResultParameter(), result.values());

        assertEquals("FILESYSTEM", parameters.parameters.get(OrchestrationParameter.CONTAINER_CONTAINERTYPE));
        assertEquals("/usr/local/camunda/backups/zeebe", parameters.parameters.get(OrchestrationParameter.CONTAINER_BASEPATH));
        // a filesystem has no bucket
        assertFalse(parameters.parameters.containsKey(OrchestrationParameter.CONTAINER_BUCKETNAME));
    }

    @Test
    void thePropertyOfAConfigurationFileIsAlsoRead() {
        String properties = """
                {"propertySources":[{"name":"applicationConfig: [file:/usr/local/camunda/config/application.yaml]","properties":{
                  "camunda.data.primary-storage.backup.store":{"value":"GCS"},
                  "camunda.data.primary-storage.backup.gcs.bucket-name":{"value":"my-bucket"},
                  "camunda.data.primary-storage.backup.gcs.base-path":{"value":"zeebe"}}}]}
                """;
        AccessActuator.Result result = AccessActuator.parse(properties, OrchestrationParameter.ENVIRONMENT);

        OrchestrationParameter.ResultParameter parameters = OrchestrationParameter.fullFillFromEnvironment(new OrchestrationParameter.ResultParameter(), result.values());
        assertEquals("GCS", parameters.parameters.get(OrchestrationParameter.CONTAINER_CONTAINERTYPE));
        assertEquals("my-bucket", parameters.parameters.get(OrchestrationParameter.CONTAINER_BUCKETNAME));
        assertEquals("zeebe", parameters.parameters.get(OrchestrationParameter.CONTAINER_BASEPATH));
    }

    @Test
    void theOldVariablesOfZeebe88AreRead() {
        String legacy = """
                {"propertySources":[{"name":"systemEnvironment","properties":{
                  "ZEEBE_BROKER_DATA_BACKUP_STORE":{"value":"S3"},
                  "ZEEBE_BROKER_DATA_BACKUP_S3_BUCKETNAME":{"value":"b"}}}]}
                """;
        AccessActuator.Result result = AccessActuator.parse(legacy, OrchestrationParameter.ENVIRONMENT);
        assertEquals("S3", result.values().get(OrchestrationParameter.CONTAINER_CONTAINERTYPE));
        assertEquals("b", result.values().get("s3.bucketName"));
    }

    @Test
    void hiddenValuesAreReportedWithTheWayToShowThem() {
        AccessActuator.Result result = AccessActuator.parse(MASKED, OrchestrationParameter.ENVIRONMENT);

        assertTrue(result.reachable());
        assertTrue(result.masked());
        assertTrue(result.values().isEmpty());
        assertTrue(result.note().contains("MANAGEMENT_ENDPOINT_ENV_SHOWVALUES=ALWAYS"), result.note());
    }

    @Test
    void anAnswerWhichIsNotJsonIsNotAnException() {
        AccessActuator.Result result = AccessActuator.parse("<html>", Map.of("store", java.util.List.of("A")));
        assertTrue(result.values().isEmpty());
        assertFalse(result.note().isEmpty());
    }
}
