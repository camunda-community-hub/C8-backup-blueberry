package io.camunda.blueberry.connect.secondary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.*;
import io.camunda.blueberry.connect.container.Container;
import io.camunda.blueberry.connect.container.ContainerFactory;
import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.ElasticsearchException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.exception.SnapshotRepositoryError;
import io.camunda.blueberry.operation.OperationLog;
import io.camunda.blueberry.platform.componentconfig.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ElasticSearchConnect implements SecondaryStorageInt {
    /**
     * Prefix of the name of the snapshot of the Zeebe records (documentation of the backup, step 6):
     * camunda_zeebe_records_backup_{backupId}
     */
    public static final String ZEEBE_RECORD_SNAPSHOT_PREFIX = "camunda_zeebe_records_backup_";
    /**
     * The snapshot of the Zeebe records: with the prefix of the documentation, or with the backup id only (the older name of Blueberry)
     */
    private static final Pattern ZEEBE_RECORD_SNAPSHOT = Pattern.compile("^(?:" + ZEEBE_RECORD_SNAPSHOT_PREFIX + ")?(\\d+)$");
    /**
     * The snapshots of the history backup (Operate, Tasklist): camunda_webapps_{backupId}_{version}_part_k_of_n
     */
    private static final Pattern WEBAPPS_SNAPSHOT = Pattern.compile("^camunda_webapps_(\\d+)_.+_part_\\d+_of_\\d+$");
    private final ZeebeRestApi zeebeRestApi;
    private final ElasticsearchRepositories repositories;
    private final ObjectMapper objectMapper = new ObjectMapper();
    Logger logger = LoggerFactory.getLogger(ElasticSearchConnect.class);
    BlueberryConfig blueberryConfig;
    ContainerFactory containerFactory;

    /* ******************************************************************** */
    /*  Restore (Camunda 8.10 Restore API: the cluster is in recovery mode) */
    /* ******************************************************************** */
    @Autowired
    private RestTemplate restTemplate; // Injected instance

    public ElasticSearchConnect(BlueberryConfig blueberryConfig, ContainerFactory containerFactory, ZeebeRestApi zeebeRestApi,
                                ElasticsearchRepositories repositories) {
        this.repositories = repositories;
        this.blueberryConfig = blueberryConfig;
        this.containerFactory = containerFactory;
        this.zeebeRestApi = zeebeRestApi;
    }

    /**
     * An Elasticsearch error is a JSON object with an "error" key at the top level.
     * Do not search the word "error" in the whole body: a valid answer can contain it, for example
     * a template with a field named "error" (Kibana reporting).
     */
    static boolean hasTopLevelElasticsearchError(String body, ObjectMapper objectMapper) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.isObject() && root.has("error");
        } catch (Exception e) {
            return false;
        }
    }

    private static OperationException esError(String message) {
        return new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 500,
                "Elasticsearch restore failed", message);
    }

    /* ******************************************************************** */
    /*  Restore: the calls to Elasticsearch                                 */
    /* ******************************************************************** */

    static String zeebeRecordSnapshotName(long backupId) {
        return ZEEBE_RECORD_SNAPSHOT_PREFIX + backupId;
    }

    /**
     * @return the backup id of a snapshot of the Zeebe records, null if the snapshot is something else (the snapshots of Operate,
     * Tasklist or Optimize can be in the same repository)
     */
    static Long zeebeRecordBackupId(String snapshotName) {
        if (snapshotName == null) {
            return null;
        }
        Matcher matcher = ZEEBE_RECORD_SNAPSHOT.matcher(snapshotName);
        return matcher.matches() ? Long.valueOf(matcher.group(1)) : null;
    }

    /**
     * @return the backup id of a snapshot of the secondary storage: the history backup (webapps) or the Zeebe records. Null for the
     * other snapshots (Optimize)
     */
    static Long secondaryStorageBackupId(String snapshotName) {
        if (snapshotName == null) {
            return null;
        }
        Matcher matcher = WEBAPPS_SNAPSHOT.matcher(snapshotName);
        return matcher.matches() ? Long.valueOf(matcher.group(1)) : zeebeRecordBackupId(snapshotName);
    }

    /**
     * @return the state of the snapshot in the answer of the creation (wait_for_completion=true), null if there is none
     */
    private static String snapshotState(String body) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("snapshot").path("state");
            return state.isMissingNode() ? null : state.asText();
        } catch (Exception e) {
            return null;
        }
    }

    public String getName() {
        return "ElasticSearch";
    }

    @Override
    public boolean supportsRestore() {
        return true;
    }

    /**
     * The snapshots of the backup, and the index templates: nothing is changed
     */
    @Override
    public void checkRestorable(long backupId, OperationLog operationLog) throws OperationException {
        List<ElasticsearchSnapshotPlan.Planned> plan = planSnapshots(backupId);
        operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "Elasticsearch: " + plan.size() + " snapshots found for the backup "
                + backupId + ": " + plan.stream().map(ElasticsearchSnapshotPlan.Planned::name).toList());

        // The restore does not recreate the templates: Camunda creates them when it starts on an empty datastore
        List<String> templates = listIndexTemplateNames();
        boolean hasTemplates = templates.stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains("zeebe-record"))
                && templates.stream().anyMatch(t -> {
            String name = t.toLowerCase(Locale.ROOT);
            return name.contains("operate") || name.contains("tasklist") || name.contains("camunda");
        });
        if (!hasTemplates) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400, "Index templates missing",
                    "The index templates of Zeebe, Operate or Tasklist are not in Elasticsearch. On a new Elasticsearch, start Camunda "
                            + "once so it creates them (curl -s " + blueberryConfig.getElasticsearchUrl() + "/_index_template), then restore.");
        }
    }

    /**
     * Procedure of the documentation (steps 3c and 3d): delete the indices of Camunda, then restore each snapshot
     */
    @Override
    public void restore(long backupId, OperationLog operationLog) throws OperationException {
        List<ElasticsearchSnapshotPlan.Planned> plan = planSnapshots(backupId);

        for (String index : listIndices()) {
            if (ElasticsearchSnapshotPlan.isCamundaIndex(index)) {
                operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "Delete the index " + index);
                deleteIndex(index);
            }
        }
        for (ElasticsearchSnapshotPlan.Planned snapshot : plan) {
            operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "Restore the snapshot " + snapshot.name()
                    + " from the repository " + snapshot.repository());
            restoreSnapshot(snapshot.repository(), snapshot.name());
        }
    }

    /**
     * The snapshots of the backup, searched in the repositories of Blueberry
     */
    List<ElasticsearchSnapshotPlan.Planned> planSnapshots(long backupId) throws OperationException {
        Map<String, List<String>> namesPerRepository = new LinkedHashMap<>();
        // The repositories of the backup: blueberry.elasticsearch.zeebeRecordRepository and blueberry.optimizeRepository.
        // The old value blueberry.zeebeRepository is not used by the backup, it is not searched (the repository may not exist: 404 repository_missing_exception)
        // The same rule as the backup: the repository declared by the cluster (actuator), else the configuration
        Parameter zeebeRecordRepository = repositories.zeebeRecord();
        Parameter optimizeRepository = repositories.optimize();
        logger.info("Snapshots searched in the repositories: Zeebe records [{}] {}, Optimize [{}] {}", zeebeRecordRepository.getValueString(),
                zeebeRecordRepository.describe(), optimizeRepository.getValueString(), optimizeRepository.describe());
        for (String repository : Arrays.asList(zeebeRecordRepository.getValueString(), optimizeRepository.getValueString())) {
            if (repository != null && !repository.isBlank() && !namesPerRepository.containsKey(repository)) {
                namesPerRepository.put(repository, listSnapshotNames(repository));
            }
        }
        return ElasticsearchSnapshotPlan.plan(backupId, namesPerRepository);
    }

    /**
     * The names of all the indices
     */
    List<String> listIndices() throws OperationException {
        String body = esCall(blueberryConfig.getElasticsearchUrl() + "/_cat/indices?h=index", HttpMethod.GET);
        List<String> indices = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String index = line.trim();
            if (!index.isEmpty()) {
                indices.add(index);
            }
        }
        return indices;
    }

    void deleteIndex(String index) throws OperationException {
        esCall(blueberryConfig.getElasticsearchUrl() + "/" + index, HttpMethod.DELETE);
    }

    /**
     * The names of the snapshots of a repository
     */
    List<String> listSnapshotNames(String repository) throws OperationException {
        String body = esCall(blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repository + "/_all", HttpMethod.GET);
        try {
            JsonNode snapshots = objectMapper.readTree(body).path("snapshots");
            List<String> names = new ArrayList<>();
            if (snapshots.isArray()) {
                for (JsonNode snapshot : snapshots) {
                    names.add(snapshot.path("snapshot").asText());
                }
            }
            return names;
        } catch (Exception e) {
            throw esError("Cannot read snapshots in repository " + repository + ". " + e.getMessage());
        }
    }

    /**
     * Restore a snapshot, and wait for the end. No index filter
     */
    void restoreSnapshot(String repository, String snapshot) throws OperationException {
        esCall(blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repository + "/" + snapshot + "/_restore?wait_for_completion=true", HttpMethod.POST);
    }

    /**
     * Names of all the templates. Two kinds exist in Elasticsearch, and Camunda uses both:
     * <ul>
     *   <li>composable templates (_index_template): Zeebe, Operate, Tasklist</li>
     *   <li>legacy templates (_template): Optimize, for example optimize-variable-update-instance_v2</li>
     * </ul>
     */
    List<String> listIndexTemplateNames() throws OperationException {
        List<String> names = new ArrayList<>();
        String body = esCall(blueberryConfig.getElasticsearchUrl() + "/_index_template", HttpMethod.GET);
        try {
            JsonNode templates = objectMapper.readTree(body).path("index_templates");
            if (templates.isArray()) {
                for (JsonNode template : templates) {
                    names.add(template.path("name").asText());
                }
            }
        } catch (Exception e) {
            throw esError("Cannot read index templates. " + e.getMessage());
        }

        // Legacy templates: the answer is an object, the keys are the names of the templates
        try {
            String legacyBody = esCall(blueberryConfig.getElasticsearchUrl() + "/_template", HttpMethod.GET);
            objectMapper.readTree(legacyBody).fieldNames().forEachRemaining(names::add);
        } catch (Exception e) {
            // Not fatal: a cluster may not support the legacy templates API
        }
        return names;
    }

    /**
     * One call to Elasticsearch, without body
     *
     * @return the body of the answer. An error of Elasticsearch (a status other than 2xx, or a top level "error" in the answer) is an OperationException
     */
    private String esCall(String url, HttpMethod method) throws OperationException {
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, method, HttpEntity.EMPTY, String.class);
            String body = response.getBody() == null ? "" : response.getBody();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw esError("Elasticsearch " + method + " " + url + " returned " + response.getStatusCode().value());
            }
            if (hasTopLevelElasticsearchError(body, objectMapper)) {
                throw esError("Elasticsearch " + method + " " + url + " failed: " + (body.length() > 500 ? body.substring(0, 500) : body));
            }
            return body;
        } catch (OperationException e) {
            throw e;
        } catch (org.springframework.web.client.RestClientException e) {
            throw esError("Elasticsearch " + method + " " + url + " failed: " + e.getMessage());
        }
    }

    public OperationResult connection() {
        OperationResult operationResult = new OperationResult();
        operationResult.success = true;
        return operationResult;
    }

    @Override
    public PingStatus ping() {
        return isConnected() ? PingStatus.CONNECTED : PingStatus.NOT_CONNECTED;
    }

    public boolean isConnected() {
        String url = blueberryConfig.getElasticsearchUrl();
        try {
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);
            // receive {"operaterepository":{"type":"azure","uuid":"7BlNbbw3TxadAqc6M523cw","settings":{"container":"elasticsearchcontainer","base_path":"operatebackup"}}}
            if (response.getStatusCode() != HttpStatus.OK) {
                logger.error("Elasticsearch connection failed with status " + response.getStatusCode());
                return false;
            }
            logger.info("Elasticsearch connection successful");
            return true;
        } catch (Exception e) {
            logger.error("Can't access ElasticSearch url[{}] {} ", url, e);
            return false;
        }
    }

    /**
     * Is Elasticsearch there? The cluster first (it answers on its URL), else the configuration (blueberry.elasticsearch.enabled)
     */
    public boolean isPresent() {
        return isConnected() || isActive();
    }

    /**
     * Return the connection information plus informatin on the way to connect, in order to give back more feedback
     *
     * @return the connection info
     */
    public ConnectionInfo isConnectedInformation() {
        return new ConnectionInfo(isConnected(), "Url Connection [" + blueberryConfig.getElasticsearchUrl() + "]");
    }

    public OperationResult existRepository(String repositoryName) {
        OperationResult operationResult = new OperationResult();
        String url = blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repositoryName;
        operationResult.command = "PUT " + url;

        try {
            // ResponseEntity<String> response = restTemplate.getForEntity(operationResult.command, String.class);
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(url, JsonNode.class);
            // receive {"operaterepository":{"type":"azure","uuid":"7BlNbbw3TxadAqc6M523cw","settings":{"container":"elasticsearchcontainer","base_path":"operatebackup"}}}
            if (response.getStatusCode() != HttpStatus.OK) {
                operationResult.success = false;
                operationResult.details = "Can't connect to ElasticSearch [" + operationResult.command + "]: Http " + response.getStatusCode().value();
                return operationResult;
            }

            JsonNode jsonNode = response.getBody();
            // is a repository is defined?
            String containerType = jsonNode == null ? null : jsonNode.path(repositoryName).path("type").asText();
            String containerUuid = jsonNode == null ? null : jsonNode.path(repositoryName).path("uuid").asText();
            operationResult.details = " ContainerType[" + containerType + "] uuid[" + containerUuid + "]";

            operationResult.resultBoolean = true;
            operationResult.success = true;

            return operationResult;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                operationResult.success = false;
                operationResult.details = "Repository [" + repositoryName + "] does not exist";
            } else {
                operationResult.success = false;
                operationResult.details = e.getMessage();
            }
            return operationResult; // If an exception occurs (e.g., 404 error), assume the repository doesn't exist
        } catch (Exception e) {
            operationResult.success = false;
            operationResult.details = e.getMessage();
            return operationResult; // If an exception occurs (e.g., 404 error), assume the repository doesn't exist
        }
    }

    /**
     * Create a repository with the command
     * <p>
     * To delete it: curl -X DELETE "http://localhost:9200/_snapshot/operaterepository"
     *
     * @param repositoryName
     * @param containerType           azure, S3...
     * @param basePathInsideContainer inside the container, the path
     * @throws ElasticsearchException exception in case of error
     */
    public OperationResult createRepository(String repositoryName,
                                            String containerType,
                                            String basePathInsideContainer) {
        OperationResult operationResult = new OperationResult();
        operationResult.command = blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repositoryName;
        Container container = containerFactory.getContainerFromType(containerType);
        if (container == null) {
            operationResult.success = false;
            operationResult.details = "Can't find container [" + containerType + "]";
            return operationResult;
        }

        String configurationProblem = container.getConfigurationProblem();
        if (configurationProblem != null) {
            operationResult.success = false;
            operationResult.details = "Can't create the repository [" + repositoryName + "]: " + configurationProblem;
            return operationResult;
        }

        String jsonPayload = container.getElasticsearchPayload(basePathInsideContainer);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Create HTTP entity with payload and headers
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonPayload, headers);

            // Make PUT request
            ResponseEntity<String> response = restTemplate.exchange(operationResult.command, HttpMethod.PUT, requestEntity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                operationResult.success = false;
                operationResult.details = "Can't create repository : code["
                        + response.getStatusCode().value() + " RepositoryName[" + repositoryName
                        + "] using container information[" + container.getInformation()
                        + "] Type[" + containerType + "] path[" + basePathInsideContainer + "] " + response.getBody();
                return operationResult;
            }

            operationResult.success = true;
            return operationResult;

        } catch (Exception e) {
            operationResult.success = false;
            operationResult.details = "Can't create repository : RepositoryName[" + repositoryName
                    + "] using container name[" + container.getInformation()
                    + "] Type[" + containerType + "] path[" + basePathInsideContainer + "] : "
                    + e.getMessage();
            return operationResult;
        }

    }

    @Override
    public boolean isActive() {
        // Only when blueberry.elasticsearch.enabled is true
        return blueberryConfig.isElasticsearchEnabled() && blueberryConfig.getElasticsearchUrl() != null;
    }

    @Override
    public BackupComponentInt.COMPONENT getComponent() {
        return BackupComponentInt.COMPONENT.SECONDARYSTORAGE;
    }

    /**
     * The backup of the secondary storage, as the documentation of the backup (Elasticsearch / OpenSearch):
     * <ol>
     *     <li>step 2: start the history backup of the applications (Operate, Tasklist): {@link #backupApplication}</li>
     *     <li>step 4: wait for the end of the history backup: {@link #waitBackupApplication}</li>
     *     <li>step 6: snapshot of the Zeebe records: {@link #backupZeebeRecord}</li>
     * </ol>
     * The step 7 (wait for the snapshot of the Zeebe records) is {@link #waitBackup}.
     */
    @Override
    public BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException {
        BackupOperation application = backupApplication(backupId, operationLog);
        if (!application.isOk()) {
            return application;
        }
        application = waitBackupApplication(backupId, operationLog);
        if (!application.isOk()) {
            return application;
        }
        return backupZeebeRecord(backupId, operationLog);
    }

    /**
     * Wait for the end of the backup of the secondary storage: the snapshot of the Zeebe records (step 7). The history backup is
     * already complete (step 4)
     */
    @Override
    public BackupOperation waitBackup(Long backupId, OperationLog operationLog) throws BackupException {
        return waitZeebeRecord(backupId, operationLog);
    }

    /**
     * Step 2 of the documentation: start the history backup of the applications (Operate, Tasklist) with the REST API of the cluster
     * <pre>
     * curl -XPOST "$ORCHESTRATION_CLUSTER_API/backups/history" -H "Content-Type: application/json" -d "{\"backupId\": $BACKUP_ID}"
     * </pre>
     * Only for Elasticsearch and OpenSearch. The cluster must know the snapshot repository
     * (camunda.data.secondary-storage.elasticsearch.backup.repository-name), else it answers 403.
     */
    public BackupOperation backupApplication(Long backupId, OperationLog operationLog) {
        BackupOperation backupOperation = new BackupOperation();
        ZeebeRestApi.RestResult start = zeebeRestApi.takeHistoryBackup(backupId);
        operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "Start the history backup (Operate, Tasklist) " + backupId
                + ": status " + start.status() + " " + (start.raw() == null ? "" : start.raw()));
        backupOperation.status = start.status();
        if (!start.isSuccess()) {
            backupOperation.title = "History backup refused (status " + start.status() + ")";
            backupOperation.message = "POST " + zeebeRestApi.getBaseUrl() + "/backups/history answered " + start.status() + " " + start.raw();
        }
        return backupOperation;
    }

    /**
     * Step 4 of the documentation: wait for the end of the history backup, GET /v2/backups/history/{backupId} until the state is
     * COMPLETED
     */
    public BackupOperation waitBackupApplication(Long backupId, OperationLog operationLog) {
        BackupOperation backupOperation = new BackupOperation();
        int timeToWait = 500;
        for (int loop = 0; loop < 2000; loop++) {
            try {
                Thread.sleep(timeToWait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                backupOperation.status = 500;
                backupOperation.title = "History backup interrupted";
                return backupOperation;
            }
            if (loop > 20) {
                timeToWait = 2000;
            }
            ZeebeRestApi.RestResult state = zeebeRestApi.getHistoryBackup(backupId);
            if (!state.isSuccess() || state.body() == null) {
                // 404: the backup is not visible yet
                continue;
            }
            String stateName = state.body().path("state").asText();
            if ("COMPLETED".equalsIgnoreCase(stateName)) {
                operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "History backup " + backupId + " completed");
                backupOperation.status = 202;
                return backupOperation;
            }
            if ("FAILED".equalsIgnoreCase(stateName) || "INCOMPLETE".equalsIgnoreCase(stateName) || "INCOMPATIBLE".equalsIgnoreCase(stateName)) {
                backupOperation.status = 500;
                backupOperation.title = "History backup " + backupId + " is " + stateName;
                backupOperation.message = state.body().path("failureReason").asText(state.raw());
                return backupOperation;
            }
        }
        backupOperation.status = 500;
        backupOperation.title = "History backup " + backupId + " is too long";
        backupOperation.message = "The history backup is not completed after the waiting time";
        return backupOperation;
    }

    /**
     * Step 6 of the documentation: the snapshot of the Zeebe records. It must run after the history backup is complete (step 4)
     * <pre>
     * curl -XPUT "$ELASTIC_ENDPOINT/_snapshot/$ELASTIC_SNAPSHOT_REPOSITORY/camunda_zeebe_records_backup_$BACKUP_ID?wait_for_completion=true" \
     *   -H 'Content-Type: application/json' -d '{"indices": "zeebe-record*", "feature_states": ["none"]}'
     * </pre>
     */
    public BackupOperation backupZeebeRecord(Long backupId, OperationLog operationLog) {
        BackupOperation backupOperation = new BackupOperation();
        String repository = repositories.zeebeRecord().getValueString();
        String snapshot = zeebeRecordSnapshotName(backupId);
        HttpEntity<?> request = new HttpEntity<>(Map.of("indices", "zeebe-record*", "feature_states", List.of("none")));
        String url = blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repository + "/" + snapshot + "?wait_for_completion=true";
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, request, String.class);
            backupOperation.status = response.getStatusCode().value();
            operationLog.info(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "Snapshot of the Zeebe records [" + snapshot + "] in the repository ["
                    + repository + "] response: " + response.getStatusCode().value() + " [" + response.getBody() + "]");
            String state = snapshotState(response.getBody());
            if (state != null && !"SUCCESS".equals(state)) {
                backupOperation.status = 500;
                backupOperation.title = "Snapshot of the Zeebe records " + snapshot + " is " + state;
                backupOperation.message = "Url[" + url + "] " + response.getBody();
                operationLog.error(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, backupOperation.title);
            }
        } catch (Exception e) {
            logger.error("Exception in the snapshot of the Zeebe records", e);

            backupOperation.title = e.getMessage();
            backupOperation.message = "Component[" + BackupComponentInt.COMPONENT.SECONDARYSTORAGE + "] Url[" + url + "]" + e.getMessage();
            operationLog.error(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, "Error during the snapshot of the Zeebe records " + e.getMessage());
        }
        return backupOperation;
    }

    /**
     * Step 7 of the documentation: wait for the end of the snapshot of the Zeebe records. GET /_snapshot/{repository}/{snapshot}
     * until the state is SUCCESS
     */
    public BackupOperation waitZeebeRecord(Long backupId, OperationLog operationLog) {
        BackupOperation backupOperation = new BackupOperation();

        String urlComplete = blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repositories.zeebeRecord().getValueString() + "/"
                + zeebeRecordSnapshotName(backupId);
        boolean written = false;
        int timeToWait = 100;
        long totalTimeToWait = 0;
        int loopCount = 0;
        ResponseEntity<JsonNode> response = null;
        boolean backupIsFinished = false;
        do {
            logger.info("checking backup status for url {} count {}", urlComplete, loopCount);
            loopCount++;
            if (totalTimeToWait > 3000) {
                timeToWait = 500;
            }
            if (totalTimeToWait > 10000) {
                timeToWait = 1000;
            }
            if (totalTimeToWait > 30000) {
                timeToWait = 5000;
            }

            try {
                Thread.sleep(timeToWait);
                totalTimeToWait += timeToWait;

            } catch (InterruptedException e) {
                // do nothing
            }

            try {
                response = restTemplate.getForEntity(urlComplete, JsonNode.class);
            } catch (Exception e) {
                logger.error("Exception in the wait of the snapshot of the Zeebe records", e);
                backupOperation.status = 400;
                backupOperation.title = e.getMessage();
                backupOperation.message = "Component[" + BackupComponentInt.COMPONENT.SECONDARYSTORAGE + "] Url[" + urlComplete + "]" + e.getMessage();

                return backupOperation;
            }
            JsonNode jsonNode = response.getBody();
            backupOperation.status = response.getStatusCode().value();

            logger.info("backup status response for url {}: {}, {}", urlComplete, response.getStatusCode().value(), response.getBody());
            // Check status
            backupIsFinished = true;
            JsonNode snapshots = jsonNode == null ? null : jsonNode.path("snapshots");
            if (snapshots != null) {
                for (JsonNode snapshot : snapshots) {
                    String snapshotName = snapshot.path("snapshot").asText();
                    String state = snapshot.path("state").asText();
                    if ("FAILED".equals(state) || "PARTIAL".equals(state) || "INCOMPATIBLE".equals(state)) {
                        // it will never be a success: do not wait for ever
                        backupOperation.status = 500;
                        backupOperation.title = "Snapshot of the Zeebe records " + snapshotName + " is " + state;
                        backupOperation.message = "Url[" + urlComplete + "]";
                        return backupOperation;
                    }
                    if (!"SUCCESS".equals(state)) {
                        backupIsFinished = false;
                    }
                    if (!written) {
                        operationLog.addSnapshotName(BackupComponentInt.COMPONENT.SECONDARYSTORAGE, snapshotName);
                    }
                }
            }
            written = true; //only write once
        }
        while (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && !backupIsFinished);
        return backupOperation;
    }

    @Override
    public List<BackupInfo> getListBackups() throws OperationException {

        String urlComplete = getUrlListBackup();
        try {
            // http://localhost:9200/_snapshot/camunda_zeebe_records_backup/_all?pretty
            ResponseEntity<JsonNode> response = restTemplate.getForEntity(urlComplete, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful())
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, response.getStatusCode().value(), "Can't access ES", "Can't access ES");

            JsonNode jsonNode = response.getBody();
            JsonNode snapshots = jsonNode.path("snapshots");
            // One backup per backup id: the history backup (webapps snapshots) and the snapshot of the Zeebe records belong to the same
            // backup. The snapshot of the Zeebe records exists only when Optimize is active: the history backup is enough.
            // The snapshots of Optimize are in the backup of Optimize.
            Map<Long, BackupInfo> backups = new LinkedHashMap<>();
            for (JsonNode snapshot : snapshots) {
                Long snapshotBackupId = secondaryStorageBackupId(snapshot.path("snapshot").asText());
                if (snapshotBackupId == null) {
                    continue;
                }
                boolean success = "SUCCESS".equals(snapshot.path("state").asText());
                // value is 2025-05-29T00:16:55.622+0000
                java.time.LocalDateTime start = Instant.parse(snapshot.path("start_time").asText())
                        .atZone(ZoneId.systemDefault()) // Use your system's time zone
                        .toLocalDateTime();
                BackupInfo backupInfo = backups.get(snapshotBackupId);
                if (backupInfo == null) {
                    backupInfo = new BackupInfo();
                    backupInfo.backupId = snapshotBackupId;
                    backupInfo.status = BackupInfo.Status.COMPLETED;
                    backupInfo.components.add(BackupComponentInt.COMPONENT.SECONDARYSTORAGE);
                    backupInfo.backupTime = start;
                    backups.put(snapshotBackupId, backupInfo);
                } else if (start.isBefore(backupInfo.backupTime)) {
                    backupInfo.backupTime = start;
                }
                if (!success) {
                    backupInfo.status = BackupInfo.Status.FAILED;
                }
            }
            List<BackupInfo> listBackups = new ArrayList<>(backups.values());
            return listBackups;
        } catch (Exception e) {
            logger.error("Can't call [{}] error {}", urlComplete, e);
            // The snapshot repository does not exist: the user must configure the platform
            String missingRepository = SnapshotRepositoryError.detect(e.getMessage());
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.BACKUP_LIST, "No Connection to ElasticSearch",
                    missingRepository != null ? SnapshotRepositoryError.message(missingRepository)
                            : "Error url[" + urlComplete + "] : " + e.getMessage());
        }
    }

    @Override
    public String getUrlListBackup() {
        return blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repositories.zeebeRecord().getValueString() + "/_all";
    }

    /* ******************************************************************** */
    /*                                                                      */
    /*  Data indexes query                                                  */
    /*                                                                      */
    /* ******************************************************************** */


    /**
     * get the list of ZeebeRecords
     * ZeebeRecords store information group by ¨Partition/<TypeSequence>/<Value>
     * for example 1/PROCESS_INSTANCE/4553
     *
     * @return a Map of Partitions. For each partition SequencePerType is a map<Sequence,Value>
     */
    public PartitionSequencesInfo getZeebeRecordsInfo() throws OperationException {
        String uri = "/zeebe-record*/_search";
        // EOL
        String dataOption = "{\"size\": 0, " // EOL
                + " \"aggs\": "// EOL
                + "  {\"value_types\": " // EOL
                + "   {\"terms\": {\"field\": \"valueType\",\"size\": 20}," // EOL
                + "    \"aggs\": {\"partitions\": " + "                {\"terms\": {\"field\": \"partitionId\"},"  // EOL
                + "                 \"aggs\": " // EOL
                + "                  { "
                + "                    \"min_sequence\": {\"min\": " + "{\"field\": \"sequence\"}}," // EOL
                + "                    \"max_sequence\": {\"max\": {\"field\": \"sequence\"}},"
                + "                     \"min_position\": {\"min\": " + "{\"field\": \"position\"}}," // EOL
                + "                    \"max_position\": {\"max\": {\"field\": \"position\"}}"
                + "                  }" // EOL
                + "                }" // EOL
                + "              }" // EOD
                + "   }" // EOL
                + "  }" // EOL
                + "}";

        logger.info("ElasticSearch getZeebeRecord URL[{}] Body[{}]", blueberryConfig.getElasticsearchUrl() + uri, dataOption);
        try {
        /*
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // entity (body + headers)
        HttpEntity<String> entity = new HttpEntity<>(dataOption, headers);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(urlComplete, entity, JsonNode.class);
        */
            // The RestTemplate of Blueberry reads the Jackson 2 JsonNode (see RestTemplateConfig). The WebClient of Spring 7 reads Jackson 3 only:
            // "Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]"
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(blueberryConfig.getElasticsearchUrl() + uri, new HttpEntity<>(dataOption, headers), JsonNode.class);
            JsonNode jsonResponse = response.getBody();

            PartitionSequencesInfo partitionSequencesPerType = new PartitionSequencesInfo();
            JsonNode buckets = jsonResponse.path("aggregations").path("value_types").path("buckets");


            // search key "process-instance
            if (buckets.isArray()) {
                for (JsonNode bucket : buckets) {
                    // Access the source of each hit and convert it to a Map
                    // typeSequence is PROCESS_INSTANCE, PROCESS_INSTANCE_CREATION, PROCESS, INCIDENT, VARIABLE, JOB, COMMAND_DISTRIBUTION, DEPLOYMENT
                    String typeSequence = bucket.get("key").asText();
                    if (typeSequence == null)
                        continue;

                    JsonNode partitions = bucket.path("partitions").path("buckets");
                    if (partitions.isArray()) {
                        for (JsonNode partition : partitions) {
                            Integer partitionId = partition.get("key").asInt();
                            JsonNode maxSequence = partition.path("max_sequence");
                            Long valueSequence = maxSequence.get("value").asLong();
                            JsonNode maxPosition = partition.path("max_position");
                            Long valuePosition = maxPosition.get("value").asLong();

                            SequencesPerType sequencesPerType = partitionSequencesPerType.getPartitionSequence(partitionId);
                            sequencesPerType.addSequence(typeSequence, valueSequence, valuePosition);
                            partitionSequencesPerType.setPartitionSequence(partitionId, sequencesPerType);


                        }
                    }
                }
            }
            return partitionSequencesPerType;
        } catch (Exception e) {
            logger.error("Can't call [{}] error {}", blueberryConfig.getElasticsearchUrl() + uri, e);
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.ZEEBERECORDS_INFO, "No Connection to ElasticSearch", "Error url[" + blueberryConfig.getElasticsearchUrl() + uri + "] : " + e.getMessage());

        }
    }


    //  curl -o optimize-import-position.json --location 'http://<opensearchHost>:<opensearchPort>/optimize-position-based-import-index*/_search?size=100&pretty=true'
    // Operate and Tasklist import positions are no longer checked: since 8.10 they are in the RDBMS.

    /**
     * get <Component> sequence. Same as ZeebeRecord, but stored in a in a different way
     *
     * @return a Map of Partitions. For each partition SequencePerType is a map<Sequence,Value>
     */
    public PartitionSequencesInfo getComponentRecordsInfo(COMPONENT_IMPORTER component) throws OperationException {

        String urlComplete = null;
        switch (component) {
            case OPTIMIZE:
                urlComplete = blueberryConfig.getElasticsearchUrl() + "/optimize-position-based-import-index*/_search?size=500";
                break;
            default:
                throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.NOT_SUPPORTED,
                        component.name() + " Not supported",
                        "This component [" + component.name() + "] is not supported");
        }
        try {

            // The RestTemplate of Blueberry reads the Jackson 2 JsonNode (see RestTemplateConfig)
            JsonNode jsonResponse = restTemplate.getForEntity(urlComplete, JsonNode.class).getBody();

            PartitionSequencesInfo partitionSequencesPerType = new PartitionSequencesInfo();


            JsonNode hits = jsonResponse.path("hits").path("hits");
            // search key "process-instance
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    // Access the source of each hit and convert it to a Map
                    JsonNode source = hit.path("_source");
                    String typeSequence = source.get("id").asText("");

                    StringTokenizer st = new StringTokenizer(typeSequence, "-");
                    int partitionId = Integer.parseInt(st.nextToken());
                    Long valueSequence = source.get("sequence").asLong();
                    Long valuePosition = source.get("position").asLong();

                    SequencesPerType sequencesPerType = partitionSequencesPerType.getPartitionSequence(partitionId);
                    sequencesPerType.addSequence(typeSequence, valueSequence, valuePosition);
                    partitionSequencesPerType.setPartitionSequence(partitionId, sequencesPerType);


                }
            }
            return partitionSequencesPerType;
        } catch (Exception e) {
            logger.error("Can't call [{}] error {}", urlComplete, e);
            throw OperationException.getInstanceFromCode(OperationException.BLUEBERRYERRORCODE.OPERATEINDEXES_INFO, "No Connection to ElasticSearch", "Error url[" + urlComplete + "] : " + e.getMessage());

        }

    }


    public enum COMPONENT_IMPORTER {OPTIMIZE}


    /* ******************************************************************** */
    /*                                                                      */
    /*  PartitionSequencesInfo                                              */
    /*                                                                      */
    /* ******************************************************************** */

    public record SequenceInfo(
            long sequence,
            long position) {
    }

    public class PartitionSequencesInfo {
        public Map<Integer, SequencesPerType> record = new HashMap<>();

        public Map<Integer, SequencesPerType> getAllPartitions() {
            return record;
        }

        public SequencesPerType getPartitionSequence(Integer partitionId) {
            if (!record.containsKey(partitionId))
                record.put(partitionId, new SequencesPerType());
            return record.get(partitionId);
        }

        public void setPartitionSequence(Integer partitionId, SequencesPerType sequence) {
            record.put(partitionId, sequence);
        }

        /**
         * getAllSequence and return the max
         *
         * @param partitionId for a specific partition
         * @return the max
         */

        public long getMaxSequence(int partitionId) {
            SequencesPerType sequencePartition = getPartitionSequence(partitionId);
            return sequencePartition.getInfo()
                    .values()
                    .stream()
                    .mapToLong(SequenceInfo::sequence)
                    .max()
                    .orElse(0L);

        }

        public long getMaxPosition(int partitionId) {
            SequencesPerType sequencePartition = getPartitionSequence(partitionId);
            return sequencePartition.getInfo()
                    .values()
                    .stream()
                    .mapToLong(SequenceInfo::position)
                    .max()
                    .orElse(0L);

        }
    }

    /* ******************************************************************** */
    /*                                                                      */
    /*  SequencesPerType                                                    */
    /*                                                                      */
    /* ******************************************************************** */
    public class SequencesPerType {
        private final Map<String, SequenceInfo> infoType = new HashMap<>();

        public void addSequence(String typeSequence, Long sequence, Long position) {
            infoType.put(typeSequence, new SequenceInfo(sequence, position));

        }

        public Map<String, SequenceInfo> getInfo() {
            return infoType;
        }
    }

}

