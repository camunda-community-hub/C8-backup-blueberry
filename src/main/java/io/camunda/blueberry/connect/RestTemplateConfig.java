package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        // Spring 7 reads JSON with Jackson 3 (tools.jackson), but the connectors use the Jackson 2 JsonNode
        // (com.fasterxml.jackson): without this converter, "Type definition error: [simple type, class
        // com.fasterxml.jackson.databind.JsonNode]" is raised. It must come before the Jackson 3 converter, but AFTER
        // the String converter: else a String response (an empty body for example) is parsed as JSON and fails
        // ("Error while extracting response for type [class java.lang.String] and content type [application/json]").
        List<HttpMessageConverter<?>> converters = restTemplate.getMessageConverters();
        int position = converters.size();
        for (int i = 0; i < converters.size(); i++) {
            String name = converters.get(i).getClass().getSimpleName();
            if (name.contains("Json") || name.contains("Jackson")) {
                position = i;
                break;
            }
        }
        converters.add(position, new MappingJackson2HttpMessageConverter(objectMapper()));
        return restTemplate;
    }

    /**
     * Spring Boot 4 auto-configures Jackson 3 (tools.jackson), not the Jackson 2 ObjectMapper used by ZeebeConnect.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
