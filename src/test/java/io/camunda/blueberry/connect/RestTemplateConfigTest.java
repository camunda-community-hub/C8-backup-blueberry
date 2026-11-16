package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 7 reads JSON with Jackson 3: the RestTemplate must still be able to return the Jackson 2 JsonNode
 * ("Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]").
 */
class RestTemplateConfigTest {

    @Test
    void stringResponseIsReadAsPlainText() {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();
        // an empty or non JSON body requested as String must not go through a JSON converter
        HttpMessageConverter<?> converter = restTemplate.getMessageConverters().stream()
                .filter(c -> c.canRead(String.class, MediaType.APPLICATION_JSON))
                .findFirst().orElseThrow();
        assertTrue(converter instanceof org.springframework.http.converter.StringHttpMessageConverter, "converter: " + converter);
    }

    @Test
    void firstJsonConverterReadsJackson2JsonNode() throws Exception {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();

        // the converter used for a JsonNode is the first one able to read it
        HttpMessageConverter<?> converter = restTemplate.getMessageConverters().stream()
                .filter(c -> c.canRead(JsonNode.class, MediaType.APPLICATION_JSON))
                .findFirst().orElseThrow();
        assertTrue(converter instanceof MappingJackson2HttpMessageConverter, "converter: " + converter);

        HttpInputMessage message = new HttpInputMessage() {
            public java.io.InputStream getBody() {
                return new ByteArrayInputStream("{\"operaterepository\":{\"type\":\"gcs\"}}".getBytes(StandardCharsets.UTF_8));
            }

            public org.springframework.http.HttpHeaders getHeaders() {
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                return headers;
            }
        };
        @SuppressWarnings("unchecked")
        JsonNode node = ((HttpMessageConverter<JsonNode>) converter).read(JsonNode.class, message);
        assertEquals("gcs", node.path("operaterepository").path("type").asText());
    }
}
