package io.camunda.blueberry.connect;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Spring Boot 4 auto-configures Jackson 3 (tools.jackson), not the Jackson 2 ObjectMapper used by ZeebeConnect.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}