package io.camunda.blueberry.connect.secondary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticSearchConnectErrorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void hasTopLevelElasticsearchErrorIgnoresNestedErrorFieldInTemplateResponse() {
        String kibanaLike = """
                {"index_templates":[{"name":".kibana-reporting","index_template":{"template":{"mappings":{"properties":{"error":{"type":"keyword"}}}}}}]}
                """;
        assertFalse(ElasticSearchConnect.hasTopLevelElasticsearchError(kibanaLike, MAPPER));
    }

    @Test
    void hasTopLevelElasticsearchErrorDetectsTopLevelErrorObject() {
        String esError = "{\"error\":{\"type\":\"index_not_found_exception\",\"reason\":\"no such index\"}}";
        assertTrue(ElasticSearchConnect.hasTopLevelElasticsearchError(esError, MAPPER));
    }

    @Test
    void hasTopLevelElasticsearchErrorIgnoresLargeSuccessfulIndexTemplatePayload() {
        String largeId = "202509021633";
        String body = "{\"index_templates\":[{\"name\":\"camunda_zeebe\",\"index_template\":{}}],\"backup_marker\":" + largeId + "}";
        assertFalse(ElasticSearchConnect.hasTopLevelElasticsearchError(body, MAPPER));
    }
}
