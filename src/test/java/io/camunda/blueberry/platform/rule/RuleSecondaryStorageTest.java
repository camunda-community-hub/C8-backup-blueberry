package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OperationResult;
import io.camunda.blueberry.connect.BackupInfo;
import io.camunda.blueberry.connect.secondary.ElasticSearchConnect;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.TestOrchestration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSecondaryStorageTest {

    private static class FakeStorage implements SecondaryStorageInt {
        public String getName() {
            return "ElasticSearch";
        }

        public boolean isActive() {
            return true;
        }

        public BackupComponentInt.COMPONENT getComponent() {
            return BackupComponentInt.COMPONENT.SECONDARYSTORAGE;
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
     * Elasticsearch: the repository exists or not; the creation is recorded
     */
    private static class FakeElasticsearch extends ElasticSearchConnect {
        final boolean exists;
        final boolean creationWorks;
        final List<String> created = new ArrayList<>();

        FakeElasticsearch(boolean exists, boolean creationWorks) {
            super(new BlueberryConfig(), null, null, null);
            this.exists = exists;
            this.creationWorks = creationWorks;
        }

        @Override
        public OperationResult existRepository(String repositoryName) {
            OperationResult result = new OperationResult();
            result.resultBoolean = exists;
            result.command = "GET /_snapshot/" + repositoryName;
            result.details = "";
            return result;
        }

        @Override
        public OperationResult createRepository(String repositoryName, String containerType, String basePathInsideContainer) {
            created.add(repositoryName);
            OperationResult result = new OperationResult();
            result.success = creationWorks;
            result.command = "PUT /_snapshot/" + repositoryName;
            result.details = creationWorks ? "" : "refused";
            return result;
        }
    }

    private static Rule.RuleInfo run(Map<String, String> configuration, String envJson, FakeElasticsearch elasticsearch, boolean configure) {
        OrchestrationParameter parameters = TestOrchestration.build(configuration, envJson);
        SecondaryStorageFactory factory = new SecondaryStorageFactory(List.of(new FakeStorage()), parameters);
        RuleSecondaryStorage rule = new RuleSecondaryStorage(factory, parameters, elasticsearch, new BlueberryConfig());
        return configure ? rule.configure() : rule.check();
    }

    private static final String ENV_REPOSITORY = "{\"propertySources\":[{\"name\":\"systemEnvironment\",\"properties\":{"
            + "\"CAMUNDA_DATA_SECONDARYSTORAGE_TYPE\":{\"value\":\"elasticsearch\"},"
            + "\"CAMUNDA_DATA_SECONDARYSTORAGE_ELASTICSEARCH_BACKUP_REPOSITORYNAME\":{\"value\":\"camunda\"}}}]}";
    private static final String ENV_NO_REPOSITORY = "{\"propertySources\":[{\"name\":\"systemEnvironment\",\"properties\":{"
            + "\"CAMUNDA_DATA_SECONDARYSTORAGE_TYPE\":{\"value\":\"elasticsearch\"}}}]}";

    @Test
    void theRepositoryNameFromTheClusterAndTheRepositoryExists() {
        FakeElasticsearch elasticsearch = new FakeElasticsearch(true, true);
        Rule.RuleInfo info = run(Map.of(), ENV_REPOSITORY, elasticsearch, false);

        assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
        assertTrue(info.getListVerifications().stream().anyMatch(v -> v.action().equals("Check existence of the Snapshot repository:[camunda] (actuator)")
                && v.actionStatus() == Rule.RuleStatus.CORRECT));
        assertTrue(info.getListVerifications().stream().anyMatch(v -> v.action().contains("is created in Elasticsearch")
                && v.actionStatus() == Rule.RuleStatus.CORRECT));
        assertTrue(elasticsearch.created.isEmpty());
    }

    @Test
    void withoutNameTheRuleFails() {
        Rule.RuleInfo info = run(Map.of(), ENV_NO_REPOSITORY, new FakeElasticsearch(true, true), false);

        assertEquals(Rule.RuleStatus.FAILED, info.getStatus());
        assertTrue(info.getListVerifications().stream().anyMatch(v -> v.action().startsWith("Snapshot name: [] (config)")
                && v.actionStatus() == Rule.RuleStatus.FAILED));
    }

    @Test
    void theNameOfTheConfigurationIsUsedButTheOrchestrationMustKnowItToo() {
        Rule.RuleInfo info = run(Map.of("backupRepositoryName", "camunda"), ENV_NO_REPOSITORY, new FakeElasticsearch(true, true), false);

        // the cluster does not give the name: an error, the history backup would be refused
        assertEquals(Rule.RuleStatus.FAILED, info.getStatus());
        assertTrue(info.getListVerifications().stream().anyMatch(v -> v.action().startsWith("Snapshot name: [camunda] (config)")
                && v.actionStatus() == Rule.RuleStatus.FAILED));
    }

    @Test
    void aMissingRepositoryFailsTheCheckAndIsCreatedByConfigure() {
        // check: FAILED, nothing is created
        FakeElasticsearch checkEs = new FakeElasticsearch(false, true);
        Rule.RuleInfo check = run(Map.of(), ENV_REPOSITORY, checkEs, false);
        assertEquals(Rule.RuleStatus.FAILED, check.getStatus());
        assertTrue(check.getListVerifications().stream().anyMatch(v -> v.action().contains("is not created") && v.actionStatus() == Rule.RuleStatus.FAILED));
        assertTrue(checkEs.created.isEmpty());

        // configure: INPROGRESS then the creation, CORRECT
        FakeElasticsearch configureEs = new FakeElasticsearch(false, true);
        Rule.RuleInfo configure = run(Map.of(), ENV_REPOSITORY, configureEs, true);
        assertEquals(Rule.RuleStatus.CORRECT, configure.getStatus());
        assertTrue(configure.getListVerifications().stream().anyMatch(v -> v.action().contains("is not created") && v.actionStatus() == Rule.RuleStatus.INPROGRESS));
        assertTrue(configure.getListVerifications().stream().anyMatch(v -> v.activity() != null && v.activity().contains("is created in Elasticsearch")
                && v.actionStatus() == Rule.RuleStatus.DONEWITHSUCCESS));
        assertEquals(List.of("camunda"), configureEs.created);
    }

    @Test
    void aFailedCreationFailsTheRule() {
        Rule.RuleInfo configure = run(Map.of(), ENV_REPOSITORY, new FakeElasticsearch(false, false), true);

        assertEquals(Rule.RuleStatus.FAILED, configure.getStatus());
        assertTrue(configure.getListVerifications().stream().anyMatch(v -> v.action().startsWith("Create the snapshot repository") && v.actionStatus() == Rule.RuleStatus.FAILED));
        assertFalse(configure.getListVerifications().isEmpty());
    }
}

