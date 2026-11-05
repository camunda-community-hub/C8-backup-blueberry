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
    public List<String> listIndexTemplateNames() throws OperationException {
        String body = get(blueberryConfig.getElasticsearchUrl() + "/_index_template");
        try {
            JsonNode templates = objectMapper.readTree(body).path("index_templates");
            List<String> names = new ArrayList<>();
            if (templates.isArray()) {
                for (JsonNode template : templates) {
                    names.add(template.path("name").asText());
                }
            }
            return names;
        } catch (Exception e) {
            throw esError("Cannot read index templates. " + e.getMessage());
        }
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
            if (body.contains("\"error\"")) {
                throw esError("Elasticsearch " + method + " " + url + " failed: " + trim(body));
            }
            return body;
        } catch (OperationException e) {
            throw e;
        } catch (RestClientException e) {
            throw esError("Elasticsearch " + method + " " + url + " failed: " + e.getMessage());
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
