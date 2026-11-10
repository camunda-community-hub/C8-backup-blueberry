package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.restoration.ElasticsearchRestoreApi;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch HTTP calls for restore. Snapshot restore posts an empty body, with no index filter.
 */
@Component
public class HttpElasticsearchRestore implements ElasticsearchRestoreApi {

    private final BlueberryConfig blueberryConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpElasticsearchRestore(BlueberryConfig blueberryConfig, RestTemplate restTemplate) {
        this.blueberryConfig = blueberryConfig;
        this.restTemplate = restTemplate;
    }

    @Override
    public List<String> listIndices() throws OperationException {
        String body = get(blueberryConfig.getElasticsearchUrl() + "/_cat/indices?h=index");
        List<String> indices = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String index = line.trim();
            if (!index.isEmpty()) {
                indices.add(index);
            }
        }
        return indices;
    }

    @Override
    public void deleteIndex(String index) throws OperationException {
        exchange(blueberryConfig.getElasticsearchUrl() + "/" + index, HttpMethod.DELETE);
    }

    @Override
    public List<String> listSnapshotNames(String repository) throws OperationException {
        String body = get(blueberryConfig.getElasticsearchUrl() + "/_snapshot/" + repository + "/_all");
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

    @Override
    public void restoreSnapshot(String repository, String snapshot) throws OperationException {
        String url = blueberryConfig.getElasticsearchUrl()
                + "/_snapshot/" + repository + "/" + snapshot + "/_restore?wait_for_completion=true";
        exchange(url, HttpMethod.POST);
    }

    @Override
    public String baseUrl() {
        return blueberryConfig.getElasticsearchUrl();
    }

    /**
     * Names of all the templates. Two kinds exist in Elasticsearch, and Camunda uses both:
     * <ul>
     *   <li>composable templates (_index_template): Zeebe, Operate, Tasklist</li>
     *   <li>legacy templates (_template): Optimize, for example optimize-variable-update-instance_v2</li>
     * </ul>
     */
    @Override
    public List<String> listIndexTemplateNames() throws OperationException {
        List<String> names = new ArrayList<>();
        String body = get(blueberryConfig.getElasticsearchUrl() + "/_index_template");
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
            String legacyBody = get(blueberryConfig.getElasticsearchUrl() + "/_template");
            objectMapper.readTree(legacyBody).fieldNames().forEachRemaining(names::add);
        } catch (Exception e) {
            // Not fatal: a cluster may not support the legacy templates API
        }
        return names;
    }

    private String get(String url) throws OperationException {
        return exchange(url, HttpMethod.GET);
    }

    private String exchange(String url, HttpMethod method) throws OperationException {
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, method, HttpEntity.EMPTY, String.class);
            String body = response.getBody() == null ? "" : response.getBody();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw esError("Elasticsearch " + method + " " + url + " returned " + response.getStatusCode().value());
            }
            if (hasTopLevelElasticsearchError(body, objectMapper)) {
                throw esError("Elasticsearch " + method + " " + url + " failed: " + trim(body));
            }
            return body;
        } catch (OperationException e) {
            throw e;
        } catch (RestClientException e) {
            throw esError("Elasticsearch " + method + " " + url + " failed: " + e.getMessage());
        }
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

    private static String trim(String body) {
        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
