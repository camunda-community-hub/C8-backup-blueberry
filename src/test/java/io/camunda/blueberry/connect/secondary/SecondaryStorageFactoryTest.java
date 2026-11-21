package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SecondaryStorageFactoryTest {

    /**
     * A secondary storage which does nothing
     */
    private static class FakeStorage implements SecondaryStorageInt {
        private final String name;

        FakeStorage(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public boolean isActive() {
            return true;
        }

        public CamundaApplicationInt.COMPONENT getComponent() {
            return CamundaApplicationInt.COMPONENT.SECONDARYSTORAGE;
        }

        public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
            return new BackupOperation();
        }

        public BackupOperation waitBackup(Long backupId, OperationLog operationLog) throws BackupException {
            return new BackupOperation();
        }

        public List<BackupInfo> getListBackups() throws OperationException {
            return List.of();
        }

        public String getUrlListBackup() {
            return "";
        }
    }

    /**
     * Zeebe answers the /actuator/env with the given JSON (null: the call fails)
     */
    private static SecondaryStorageDetector detector(String configured, String envJson) {
        return TestDetector.detector(configured, envJson);
    }

    private static final String ENV_ELASTICSEARCH = "{\"propertySources\":[{\"name\":\"systemEnvironment\",\"properties\":{"
            + "\"CAMUNDA_DATA_SECONDARYSTORAGE_TYPE\":{\"value\":\"elasticsearch\"}}}]}";
    private static final String ENV_RDBMS = "{\"propertySources\":[{\"name\":\"applicationConfig\",\"properties\":{"
            + "\"camunda.data.secondary-storage.type\":{\"value\":\"rdbms\"}}}]}";
    private static final String ENV_MASKED = "{\"propertySources\":[{\"name\":\"systemEnvironment\",\"properties\":{"
            + "\"CAMUNDA_DATA_SECONDARYSTORAGE_TYPE\":{\"value\":\"******\"}}}]}";

    @Test
    void nameIsMatchedWhateverTheCaseAndTheSeparators() {
        SecondaryStorageInt elasticsearch = new FakeStorage("ElasticSearch");
        SecondaryStorageInt sqlServer = new FakeStorage("SqlServer");
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(elasticsearch, sqlServer), detector("", null));

        assertSame(elasticsearch, factory.getSecondaryStorage("elasticsearch"));
        assertSame(elasticsearch, factory.getSecondaryStorage("ELASTIC-SEARCH"));
        assertSame(sqlServer, factory.getSecondaryStorage("SQL Server"));
        assertSame(sqlServer, factory.getSecondaryStorage("sql_server"));
        assertNull(factory.getSecondaryStorage("postgres"));
        assertNull(factory.getSecondaryStorage(""));
        assertNull(factory.getSecondaryStorage(null));
        assertEquals(List.of("ElasticSearch", "SqlServer"), factory.getSupportedNames());
    }

    @Test
    void theClusterGivesTheSecondaryStorage() {
        SecondaryStorageInt elasticsearch = new FakeStorage("ElasticSearch");
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(elasticsearch), detector("", ENV_ELASTICSEARCH));

        SecondaryStorageDetector.Detection detection = factory.detect();
        assertEquals("elasticsearch", detection.value());
        assertEquals(SecondaryStorageDetector.SOURCE_CLUSTER, detection.source());
        assertSame(elasticsearch, factory.getSecondaryStorage());
    }

    @Test
    void theConfigurationIsTheOptionBWhenTheClusterDoesNotAnswer() {
        SecondaryStorageDetector.Detection detection = detector("sqlserver", null).detect();
        assertEquals("sqlserver", detection.value());
        assertEquals(SecondaryStorageDetector.SOURCE_CONFIGURATION, detection.source());
        assertEquals("", detection.detected());
    }

    @Test
    void hiddenValuesAreNotAnAnswer() {
        SecondaryStorageDetector.Detection detection = detector("elasticsearch", ENV_MASKED).detect();
        assertEquals("", detection.detected());
        assertEquals("elasticsearch", detection.value());
        assertEquals(SecondaryStorageDetector.SOURCE_CONFIGURATION, detection.source());
    }

    @Test
    void rdbmsNeedsTheVendorOfTheConfiguration() {
        SecondaryStorageDetector.Detection detection = detector("sqlserver", ENV_RDBMS).detect();
        assertEquals("rdbms", detection.detected());
        assertEquals("sqlserver", detection.value());
        assertEquals(SecondaryStorageDetector.SOURCE_CONFIGURATION, detection.source());
    }

    @Test
    void nothingKnown() {
        SecondaryStorageDetector.Detection detection = detector("", null).detect();
        assertEquals("", detection.value());
        assertEquals(SecondaryStorageDetector.SOURCE_NONE, detection.source());
    }
}
