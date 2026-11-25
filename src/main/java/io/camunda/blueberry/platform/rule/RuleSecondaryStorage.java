package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OperationResult;
import io.camunda.blueberry.connect.secondary.ElasticSearchConnect;
import io.camunda.blueberry.connect.secondary.SecondaryStorageFactory;
import io.camunda.blueberry.connect.secondary.SecondaryStorageInt;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Get the secondary storage of the cluster (Elasticsearch, OpenSearch, a database), and check that Blueberry knows how to
 * back it up.
 * The value is the parameter secondaryStorage of the orchestration cluster: it is read in the actuator /actuator/env of Zeebe
 * (camunda.data.secondary-storage.type), else in blueberry.orchestrationConfig.secondaryStorage. Each verification gives the
 * value in [] and its origin in ().
 */
@Component
public class RuleSecondaryStorage implements Rule {
    private final SecondaryStorageFactory secondaryStorageFactory;
    private final OrchestrationParameter orchestrationParameter;
    private final ElasticSearchConnect elasticSearchConnect;
    private final BlueberryConfig blueberryConfig;

    RuleSecondaryStorage(SecondaryStorageFactory secondaryStorageFactory, OrchestrationParameter orchestrationParameter,
                         ElasticSearchConnect elasticSearchConnect, BlueberryConfig blueberryConfig) {
        this.secondaryStorageFactory = secondaryStorageFactory;
        this.orchestrationParameter = orchestrationParameter;
        this.elasticSearchConnect = elasticSearchConnect;
        this.blueberryConfig = blueberryConfig;
    }

    @Override
    public boolean validRule() {
        return true;
    }

    @Override
    public String getName() {
        return "Secondary storage";
    }

    @Override
    public String getExplanations() {
        return "The data of Operate and Tasklist (and the Zeebe records) are in the secondary storage: Elasticsearch, OpenSearch or a database. "
                + "Blueberry reads it in the configuration of the cluster (camunda.data.secondary-storage.type, via the actuator 'env' of Zeebe). "
                + "If the cluster does not give it, Blueberry uses the value blueberry.orchestrationConfig.secondaryStorage of its configuration. "
                + "The backup of the secondary storage is different for each type.";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of("https://docs.camunda.io/docs/self-managed/operational-guides/backup-restore/backup-and-restore/");
    }

    @Override
    public RuleInfo check() {
        return operation(false);
    }

    private RuleInfo operation(boolean execute) {
        RuleInfo ruleInfo = new RuleInfo(this);
        OrchestrationParameter.Parameter parameter = secondaryStorageFactory.getValue();
        String value = parameter.getValue()==null? "": parameter.getValue().toString();
        String origin = parameter.getOriginLabel();

        if (value.isEmpty()) {
            ruleInfo.addVerifications("Secondary storage of the cluster: [] (" + origin + ")", RuleStatus.FAILED, null);
            ruleInfo.addError("The secondary storage is unknown: the cluster does not give it, and blueberry.orchestrationConfig.secondaryStorage is not set. "
                    + "Set it to one of: " + String.join(", ", secondaryStorageFactory.getSupportedNames()));
            ruleInfo.setStatus(RuleStatus.FAILED);
            return ruleInfo;
        }
        ruleInfo.addVerifications("Secondary storage known: [" + value + "] (" + origin + ")", RuleStatus.CORRECT, null);

        // "rdbms" is a type: the database is read in the JDBC url of the cluster
        String kind = orchestrationParameter.getSecondaryStorageKind();
        if (!kind.equalsIgnoreCase(value.toString())) {
            String url = orchestrationParameter.getParameterString(OrchestrationParameter.SECONDARY_STORAGE_RDBMS_URL);
            ruleInfo.addVerifications("Database of the secondary storage [" + (kind.isEmpty() ? "unknown" : kind) + "] from the JDBC url [" + url
                    + "] (" + orchestrationParameter.getParameter(OrchestrationParameter.SECONDARY_STORAGE_RDBMS_URL).getOriginLabel() + ")",
                    kind.isEmpty() ? RuleStatus.FAILED : RuleStatus.CORRECT, null);
        }

        // Does Blueberry know how to back it up?
        SecondaryStorageInt secondaryStorage = secondaryStorageFactory.getSecondaryStorage();
        if (secondaryStorage != null) {
            ruleInfo.addVerifications("Secondary storage supported by Blueberry [" +  secondaryStorage.getName() + "] (" + origin + ")" ,
                    RuleStatus.CORRECT, null);

        } else {
            ruleInfo.addVerifications("Secondary storage not supported by Blueberry [" + value + "] (" + origin + "). Supported:"+ String.join(", ", secondaryStorageFactory.getSupportedNames()),
                    RuleStatus.FAILED, null);
            ruleInfo.addError("Blueberry can't back up the secondary storage [" + value + "]");
            ruleInfo.setStatus(RuleStatus.FAILED);
        }

        // The history backup (POST /v2/backups/history) snapshots the indices of Elasticsearch or OpenSearch: the cluster must know
        // the snapshot repository, and the repository must exist. Whatever the result of the previous verifications.
        if ("elasticsearch".equalsIgnoreCase(kind) || "opensearch".equalsIgnoreCase(kind)) {
            checkHistorySnapshot(ruleInfo, execute);
        }

        if (ruleInfo.inProgress()) {
            ruleInfo.setStatus(RuleStatus.CORRECT);
        }
        return ruleInfo;
    }

    /**
     * 1. the name of the snapshot repository is known (the cluster, else the configuration of Blueberry): CORRECT or FAILED
     * 2. the repository exists in Elasticsearch: CORRECT; else FAILED (check) or INPROGRESS (configure)
     * 3. configure: create the repository: CORRECT or FAILED
     */
    private void checkHistorySnapshot(RuleInfo ruleInfo, boolean execute) {
        OrchestrationParameter.Parameter parameter = orchestrationParameter.getParameter(OrchestrationParameter.BACKUP_REPOSITORY_NAME);
        String name = orchestrationParameter.getParameterString(OrchestrationParameter.BACKUP_REPOSITORY_NAME);
        String origin = parameter == null ? "config" : parameter.getOriginLabel();

        // 1. The name must exist AND come from orchestration
        if (name.isEmpty() || parameter.getOrigin().equals(OrchestrationParameter.Parameter.Origin.CONFIG)) {
            ruleInfo.addVerifications("Snapshot name: ["+name+"] (" + origin + ") : orchestration does not know that name and will failed.", RuleStatus.FAILED, null);
            ruleInfo.addError("Orchestration does not know the name of the Elasticsearch snapshot: POST /v2/backups/history will be refused by the cluster "
                    + "(403 No backup repository configured). Set CAMUNDA_DATA_SECONDARYSTORAGE_ELASTICSEARCH_BACKUP_REPOSITORYNAME (camunda.data.secondary-storage.elasticsearch.backup.repository-name) on the cluster"
                    + "or blueberry.orchestrationConfig.backupRepositoryName in Blueberry");
            ruleInfo.setStatus(RuleStatus.FAILED);
            return;
        }
        ruleInfo.addVerifications("Check existence of the Snapshot repository:[" + name + "] (" + origin + ")", RuleStatus.CORRECT, null);

        // 2. The repository exists in Elasticsearch?
        OperationResult exist = elasticSearchConnect.existRepository(name);
        if (exist.resultBoolean) {
            ruleInfo.addVerifications("Snapshot repository:[" + name + "] (" + origin + ") is created in Elasticsearch", RuleStatus.CORRECT, exist.command);
            return;
        }
        ruleInfo.addVerifications("Snapshot repository:[" + name + "] (" + origin + ") is not created in Elasticsearch",
                execute ? RuleStatus.INPROGRESS : RuleStatus.FAILED, exist.command);
        if (!execute) {
            ruleInfo.addError("The snapshot repository:[" + name + "] does not exist in Elasticsearch: use Configure to create it");
            ruleInfo.setStatus(RuleStatus.FAILED);
            return;
        }

        // 3. Create it
        OperationResult created = elasticSearchConnect.createRepository(name,
                orchestrationParameter.getParameterString(OrchestrationParameter.CONTAINER_CONTAINERTYPE),
                blueberryConfig.getZeebeRecordContainerBasePath());
        if (created.success) {
            ruleInfo.addActivity("Snapshot repository [" + name + "] (" + origin + ") is created in Elasticsearch", RuleStatus.DONEWITHSUCCESS, created.command);
        } else {
            ruleInfo.addVerifications("Create the snapshot repository [" + name + "] (" + origin + ") in Elasticsearch", RuleStatus.FAILED, created.command);
            ruleInfo.addError("Error when creating the snapshot repository in Elasticsearch: " + created.details);
            ruleInfo.setStatus(RuleStatus.FAILED);
        }
    }

    @Override
    public RuleInfo configure() {
        // Creates the snapshot repository of the history backup when it is missing
        return operation(true);
    }
}
