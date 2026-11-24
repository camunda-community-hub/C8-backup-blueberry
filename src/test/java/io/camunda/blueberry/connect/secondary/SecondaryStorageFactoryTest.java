package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.CamundaApplicationInt;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.TestOrchestration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
     * Zeebe answers the /actuator/env with the given JSON (null: the call fails). configured: blueberry.orchestrationConfig.secondaryStorage
     */
    private static OrchestrationParameter parameters(String configured, String envJson) {
        Map<String, String> configuration = configured.isEmpty() ? Map.of() : Map.of(OrchestrationParameter.SECONDARY_STORAGE.name(), configured);
        return TestOrchestration.build(configuration, envJson);
    }

    private static final String ENV_ELASTICSEARCH = "{\"propertySources\":[{\"name\":\"systemEnvironment\",\"properties\":{"
            + "\"CAMUNDA_DATA_SECONDARYSTORAGE_TYPE\":{\"value\":\"elasticsearch\"}}}]}";
    private static final String ENV_RDBMS = "{\"propertySources\":[{\"name\":\"applicationConfig\",\"properties\":{"
            + "\"camunda.data.secondary-storage.type\":{\"value\":\"rdbms\"},"
            + "\"camunda.data.secondary-storage.rdbms.url\":{\"value\":\"jdbc:sqlserver://db:1433;databaseName=camunda\"}}}]}";
    private static final String ENV_MASKED = "{\"propertySources\":[{\"name\":\"systemEnvironment\",\"properties\":{"
            + "\"CAMUNDA_DATA_SECONDARYSTORAGE_TYPE\":{\"value\":\"******\"}}}]}";

    @Test
    void nameIsMatchedWhateverTheCaseAndTheSeparators() {
        SecondaryStorageInt elasticsearch = new FakeStorage("ElasticSearch");
        SecondaryStorageInt sqlServer = new FakeStorage("SqlServer");
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(elasticsearch, sqlServer), parameters("", null));

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
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(elasticsearch), parameters("", ENV_ELASTICSEARCH));

        assertEquals("elasticsearch", factory.getValueString());
        assertEquals(OrchestrationParameter.Parameter.Origin.ACTUATOR, factory.getValue().getOrigin());
        assertEquals("actuator", factory.getValue().getOriginLabel());
        assertSame(elasticsearch, factory.getSecondaryStorage());
    }

    @Test
    void theConfigurationIsUsedWhenTheClusterDoesNotAnswer() {
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(), parameters("sqlserver", null));
        assertEquals("sqlserver", factory.getValueString());
        assertEquals(OrchestrationParameter.Parameter.Origin.CONFIG, factory.getValue().getOrigin());
    }

    @Test
    void hiddenValuesAreNotAnAnswer() {
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(), parameters("elasticsearch", ENV_MASKED));
        assertEquals("elasticsearch", factory.getValueString());
        assertEquals(OrchestrationParameter.Parameter.Origin.CONFIG, factory.getValue().getOrigin());
    }

    @Test
    void rdbmsIsTheValueOfTheClusterAndTheJdbcUrlGivesTheDatabase() {
        SecondaryStorageInt sqlServer = new FakeStorage("SqlServer");
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(sqlServer), parameters("", ENV_RDBMS));
        // the value is the one of the cluster
        assertEquals("rdbms", factory.getValueString());
        assertEquals(OrchestrationParameter.Parameter.Origin.ACTUATOR, factory.getValue().getOrigin());
        // the object is the one of the database in the JDBC url
        assertSame(sqlServer, factory.getSecondaryStorage());
    }

    @Test
    void nothingKnown() {
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(), parameters("", null));
        assertEquals("", factory.getValueString());
        assertNull(factory.getValue().getValue());
        assertNull(factory.getSecondaryStorage());
    }
}
