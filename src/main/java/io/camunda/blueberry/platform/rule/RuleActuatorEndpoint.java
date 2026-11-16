package io.camunda.blueberry.platform.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Check that a component exposes the actuator endpoints blueberry needs.
 * Each endpoint is one verification: when it is not accessible, the error says how to expose it in the Helm values.
 */
public class RuleActuatorEndpoint implements Rule {

    /**
     * An endpoint to check.
     * <ul>
     *   <li>ACCESS: the endpoint answers a GET with a 2xx status</li>
     *   <li>LINK: the endpoint is declared in the /actuator list. Used for endpoints that can't be called without side effect (exporting/pause)</li>
     * </ul>
     */
    public enum CheckType {ACCESS, LINK}

    public record EndpointCheck(String endpointId, CheckType type) {
    }

    private final String componentName;
    private final Supplier<String> actuatorUrl;
    private final List<EndpointCheck> endpoints;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RuleActuatorEndpoint(String componentName,
                                Supplier<String> actuatorUrl,
                                List<EndpointCheck> endpoints,
                                RestTemplate restTemplate) {
        this.componentName = componentName;
        this.actuatorUrl = actuatorUrl;
        this.endpoints = endpoints;
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean validRule() {
        String url = actuatorUrl.get();
        return url != null && !url.isBlank();
    }

    @Override
    public String getName() {
        return componentName + " Endpoint";
    }

    @Override
    public String getExplanations() {
        return componentName + " must expose the endpoints blueberry uses";
    }

    @Override
    public List<String> getUrlDocumentation() {
        return List.of();
    }

    @Override
    public RuleInfo check() {
        RuleInfo ruleInfo = new RuleInfo(this);
        if (!validRule()) {
            ruleInfo.setStatus(RuleStatus.DEACTIVATED);
            return ruleInfo;
        }
        ruleInfo.setStatus(RuleStatus.INPROGRESS);

        List<String> notAccessible = new ArrayList<>();
        for (EndpointCheck endpoint : endpoints) {
            String url = actuatorUrl.get() + "/actuator/" + endpoint.endpointId();
            String problem = endpoint.type() == CheckType.LINK ? checkLink(endpoint) : checkAccess(url);
            if (problem == null) {
                ruleInfo.addVerifications("Access " + url, RuleStatus.CORRECT, url);
            } else {
                notAccessible.add(endpoint.endpointId());
                ruleInfo.addVerificationsWithError("Access " + url, RuleStatus.FAILED, url,
                        problem + "\n" + getFixExplanation(endpoint.endpointId()),
                        getFixConfiguration(endpoint.endpointId()));
            }
        }

        if (notAccessible.isEmpty()) {
            ruleInfo.addDetails("All endpoints are accessible");
            ruleInfo.setStatus(RuleStatus.CORRECT);
        } else {
            ruleInfo.addError("Endpoint is not accessible: " + String.join(", ", notAccessible));
            ruleInfo.setStatus(RuleStatus.FAILED);
        }
        return ruleInfo;
    }

    /**
     * Nothing can be created from blueberry: the endpoints must be exposed in the component configuration.
     */
    @Override
    public RuleInfo configure() {
        return check();
    }

    /**
     * The endpoint is reachable if the component answers. A 404 means the endpoint is not exposed.
     * Any other HTTP answer proves the endpoint is reachable, even an error like
     * 400 {"message":"No repository with name [operaterepository] could be found."}: this is a business answer, not an exposure problem.
     *
     * @return null if the endpoint is reachable, else the problem
     */
    private String checkAccess(String url) {
        try {
            restTemplate.getForEntity(url, String.class);
            return null;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                return "Endpoint " + url + " returned 404: " + e.getResponseBodyAsString();
            }
            // 400, 401, 403, 500...: the component answered, the endpoint is exposed
            return null;
        } catch (Exception e) {
            return "Endpoint " + url + " is not accessible: " + e.getMessage();
        }
    }

    /**
     * Read the /actuator list: the endpoint is exposed if a link starts with its id (for example exporting-operationKey)
     *
     * @return null if the endpoint is declared, else the problem
     */
    private String checkLink(EndpointCheck endpoint) {
        String url = actuatorUrl.get() + "/actuator";
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode links = objectMapper.readTree(response.getBody()).path("_links");
            Iterator<String> names = links.fieldNames();
            while (names.hasNext()) {
                if (names.next().startsWith(endpoint.endpointId())) {
                    return null;
                }
            }
            return "The endpoint [" + endpoint.endpointId() + "] is not in the list " + url;
        } catch (Exception e) {
            return "Can't read the list " + url + ": " + e.getMessage();
        }
    }

    /**
     * What to fix, shown on the action line when an endpoint fails
     */
    private String getFixExplanation(String endpointId) {
        return componentName + " must expose the endpoint /actuator/" + endpointId + " on its management port. "
                + "In the Helm values, in the 'env' of the '" + componentName.toLowerCase() + "' section, "
                + "add '" + endpointId + "' to the value of management.endpoints.web.exposure.include, "
                + "and enable the access of the endpoint (Camunda 8.7 sets the access of every endpoint to none by default). "
                + "The name of the property is 'show-values', with a dash: 'show.values' is ignored, and the values of env are "
                + "then hidden (******). "
                + "If a name with dots is ignored (it happens on Zeebe), use the environment variable form: "
                + "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE, MANAGEMENT_ENDPOINT_ENV_SHOWVALUES, "
                + "MANAGEMENT_ENDPOINT_" + endpointId.toUpperCase() + "_ACCESS.";
    }

    /**
     * The Helm values to add, displayed in a box under the explanation
     */
    private String getFixConfiguration(String endpointId) {
        List<String> ids = new ArrayList<>();
        for (EndpointCheck endpoint : endpoints) {
            ids.add(endpoint.endpointId());
        }
        StringBuilder configuration = new StringBuilder();
        configuration.append("- name: management.endpoints.web.exposure.include\n")
                .append("  value: health, prometheus, loggers, usage-metrics, ").append(String.join(", ", ids)).append("\n");
        if (ids.contains("env")) {
            configuration.append("- name: management.endpoint.env.show-values\n")
                    .append("  value: ALWAYS\n");
        }
        configuration.append("- name: management.endpoint.").append(endpointId).append(".access\n")
                .append("  value: unrestricted");
        return configuration.toString();
    }
}
