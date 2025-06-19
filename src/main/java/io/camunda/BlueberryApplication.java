/* ******************************************************************** */
/*                                                                      */
/*  Blueberry application                                                 */
/*                                                                      */
/*  Spring boot application                                             */
/* ******************************************************************** */
package io.camunda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

/**
 * This Application is moved to io.camunda in order to detect all io.camunda.connector and
 * io.camunda.cherry objects
 */
@SpringBootApplication
@ConfigurationPropertiesScan("io.camunda")
@EnableScheduling
public class BlueberryApplication {

    public static void main(String[] args) {

        SpringApplication.run(BlueberryApplication.class, args);
        // thanks to Spring, the class CherryJobRunnerFactory is active. All runners (worker,
        // connectors) start then
    }

    @Bean
    public RestTemplate getRestTemplate() {
        return new RestTemplate();
    }
    // https://docs.camunda.io/docs/components/best-practices/development/writing-good-workers/

}
