package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.client.CamundaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Access to the Orchestration Cluster REST API (/v2/...), not to the Management (actuator) API.
 * <p>
 * Operations used by the backup (Camunda 8.10):
 * <ul>
 *   <li>POST /exporting/pause?soft=true, POST /exporting/resume</li>
 *   <li>POST /backups/runtime {"backupId": n}, GET /backups/runtime/{backupId}, GET /backups/runtime</li>
 *   <li>POST /backups/history {"backupId": n}, GET /backups/history/{backupId}  (Elasticsearch / OpenSearch only)</li>
 * </ul>
 * https://docs.camunda.io/docs/apis-tools/orchestration-cluster-api-rest/orchestration-cluster-api-rest-overview/
 */
@Component
public class ZeebeRestApi {
    private static final String API_PREFIX = "/v2";

    private final Logger logger = LoggerFactory.getLogger(ZeebeRestApi.class);
    private final BlueberryConfig blueberryConfig;
    // Created at the first call (not with the bean)
    private volatile RestTemplate restTemplate;
    private final CamundaClient camundaClient;
    // The response is read as a String and parsed here: it does not depend on the JSON converters of the RestTemplate
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ZeebeRestApi(BlueberryConfig blueberryConfig, CamundaClient camundaClient) {
        this.blueberryConfig = blueberryConfig;
        this.camundaClient = camundaClient;
    }

    /**
     * Answer of a REST call. A 4xx or 5xx answer is not an exception: the status and the body are given back.
     */
    public record RestResult(int status, String raw, JsonNode body) {
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * The JDK HTTP client: the default client of the RestTemplate does not accept the method PATCH (PATCH /v2/mode)
     */
    private RestTemplate restTemplate() {
        RestTemplate current = restTemplate;
        if (current == null) {
            synchronized (this) {
                if (restTemplate == null) {
                    restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());
                }
                current = restTemplate;
            }
        }
        return current;
    }

    /**
     * Base url, with the prefix /v2. Either blueberry.zeebeRestUrl, or the rest address of the Camunda client.
     */
    public String getBaseUrl() {
        String base = blueberryConfig.getZeebeRestUrl();
        if (base.isEmpty()) {
            URI restAddress = camundaClient.getConfiguration().getRestAddress();
            base = restAddress == null ? "http://localhost:8080" : restAddress.toString();
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith(API_PREFIX) ? base : base + API_PREFIX;
    }

    /* ******************************************************************** */
    /*  Exporting                                                           */
    /* ******************************************************************** */

    public RestResult pauseExporting(boolean soft) {
        return call(HttpMethod.POST, "/exporting/pause" + (soft ? "?soft=true" : ""), null);
    }

    public RestResult resumeExporting() {
        return call(HttpMethod.POST, "/exporting/resume", null);
    }

    /* ******************************************************************** */
    /*  Runtime backup (Zeebe)                                              */
    /* ******************************************************************** */

    public RestResult takeRuntimeBackup(long backupId) {
        return call(HttpMethod.POST, "/backups/runtime", Map.of("backupId", backupId));
    }

    public RestResult getRuntimeBackup(long backupId) {
        return call(HttpMethod.GET, "/backups/runtime/" + backupId, null);
    }

    public RestResult listRuntimeBackups() {
        return call(HttpMethod.GET, "/backups/runtime", null);
    }

    /* ******************************************************************** */
    /*  History backup (Operate, Tasklist data in Elasticsearch/OpenSearch) */
    /* ******************************************************************** */

    public RestResult takeHistoryBackup(long backupId) {
        return call(HttpMethod.POST, "/backups/history", Map.of("backupId", backupId));
    }

    public RestResult getHistoryBackup(long backupId) {
        return call(HttpMethod.GET, "/backups/history/" + backupId, null);
    }

    public RestResult listHistoryBackups() {
        return call(HttpMethod.GET, "/backups/history", null);
    }

    /* ******************************************************************** */
    /*  Restore API (Camunda 8.10)                                          */
    /* ******************************************************************** */

    /**
     * Change the mode of the cluster: RECOVERING (the brokers stop processing and wait for the restore) or PROCESSING.
     * PATCH /v2/mode?mode=RECOVERING answers with a changeId.
     */
    public RestResult changeMode(String mode) {
        return call(HttpMethod.PATCH, "/mode?mode=" + mode, null);
    }

    /**
     * Restore the cluster from the backups: POST /v2/restore {"backupIds": [...]} (202: accepted, with a changeId).
     * With dryRun, the request is only validated.
     */
    public RestResult restore(List<Long> backupIds, boolean dryRun) {
        return call(HttpMethod.POST, "/restore" + (dryRun ? "?dryRun=true" : ""), Map.of("backupIds", backupIds));
    }

    /**
     * Status of the restore: IN_PROGRESS, COMPLETED, FAILED, CANCELLED, and the state of each partition. The API answers
     * 404 when the restore is finished.
     */
    public RestResult getRestoreStatus() {
        return call(HttpMethod.GET, "/restore", null);
    }

    public RestResult getTopology() {
        return call(HttpMethod.GET, "/topology", null);
    }

    /**
     * The monitoring of the cluster changes (management API of Zeebe): lastChange, pendingChange. It is the way to
     * know that a change (the mode, the restore) is applied.
     */
    public RestResult getClusterChanges() {
        return callUrl(HttpMethod.GET, blueberryConfig.getZeebeActuatorUrl() + "/actuator/cluster", null);
    }

    /* ******************************************************************** */
    /*  Call                                                                */
    /* ******************************************************************** */

    /**
     * Call the REST API
     *
     * @param method HTTP method
     * @param path   path after /v2, with the query
     * @param body   body, null if none
     */
    public RestResult call(HttpMethod method, String path, Object body) {
        return callUrl(method, getBaseUrl() + path, body);
    }

    /**
     * Call a complete url (the REST API, or the management actuator of Zeebe)
     */
    public RestResult callUrl(HttpMethod method, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        String username = blueberryConfig.getZeebeRestUsername();
        if (!username.isEmpty()) {
            String credentials = username + ":" + blueberryConfig.getZeebeRestPassword();
            headers.set(HttpHeaders.AUTHORIZATION, "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            logger.info("Zeebe REST {} {}", method, url);
            ResponseEntity<String> response = restTemplate().exchange(url, method, new HttpEntity<>(body, headers), String.class);
            return toResult(response.getStatusCode().value(), response.getBody());
        } catch (HttpStatusCodeException e) {
            logger.warn("Zeebe REST {} {} answered {} {}", method, url, e.getStatusCode().value(), e.getResponseBodyAsString());
            return toResult(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("Zeebe REST {} {} failed: {}", method, url, e.getMessage());
            // 0: no answer (not reachable)
            return new RestResult(0, e.getMessage(), null);
        }
    }

    private RestResult toResult(int status, String raw) {
        JsonNode node = null;
        if (raw != null && !raw.isBlank()) {
            try {
                node = objectMapper.readTree(raw);
            } catch (Exception e) {
                // not a JSON body: keep the raw text
            }
        }
        return new RestResult(status, raw, node);
    }
}
