package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 7 reads JSON with Jackson 3: the RestTemplate must still be able to return the Jackson 2 JsonNode
 * ("Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]"), and a String answer
 * (an empty body for example) must stay a String.
 */
class RestTemplateConfigTest {

    @Test
    void stringResponseIsReadAsPlainText() {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();
        HttpMessageConverter<?> converter = restTemplate.getMessageConverters().stream()
                .filter(c -> c.canRead(String.class, MediaType.APPLICATION_JSON))
                .findFirst().orElseThrow();
        assertTrue(converter instanceof StringHttpMessageConverter, "converter: " + converter);
    }

    @Test
    void jsonNodeIsReadWithJackson2() throws Exception {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();
        HttpMessageConverter<?> converter = restTemplate.getMessageConverters().stream()
                .filter(c -> c.canRead(JsonNode.class, MediaType.APPLICATION_JSON))
                .findFirst().orElseThrow();
        assertTrue(converter instanceof MappingJackson2HttpMessageConverter, "converter: " + converter);

        HttpInputMessage message = new HttpInputMessage() {
            public InputStream getBody() {
                return new ByteArrayInputStream("{\"name\":\"camunda\",\"version\":{\"number\":\"8.10\"}}".getBytes(StandardCharsets.UTF_8));
            }

            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                return headers;
            }
        };
        @SuppressWarnings("unchecked")
        JsonNode node = ((HttpMessageConverter<JsonNode>) converter).read(JsonNode.class, message);
        assertEquals("8.10", node.path("version").path("number").asText());
    }
}
