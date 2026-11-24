package io.camunda.blueberry.connect.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.TestOrchestration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerFilesystemTest {

    /**
     * The path is in blueberry.orchestrationConfig.filesystem.path, the actuator does not answer
     */
    private static OrchestrationParameter config(String path) {
        return TestOrchestration.build(Map.of(OrchestrationParameter.FILESYSTEM_PATH.name(), path), null);
    }

    @Test
    void theRepositoryIsAnFsRepositoryAtTheConfiguredPath() throws Exception {
        ContainerFilesystem container = new ContainerFilesystem(config("/usr/share/elasticsearch/backups/elasticsearch"));

        // the command of the docker exec:
        // {"type":"fs","settings":{"location":"/usr/share/elasticsearch/backups/elasticsearch"}}
        JsonNode payload = new ObjectMapper().readTree(container.getElasticsearchPayload("/zeeberecord"));

        assertEquals("fs", payload.path("type").asText());
        assertEquals("/usr/share/elasticsearch/backups/elasticsearch", payload.path("settings").path("location").asText());
        // the base path of the component is not used: one directory
        assertEquals(1, payload.path("settings").size());
        assertEquals("filesystem", container.getType());
        assertNull(container.getConfigurationProblem());
    }

    @Test
    void aPathWithAQuoteOrABackslashStaysValidJson() throws Exception {
        ContainerFilesystem container = new ContainerFilesystem(config("C:\\backups\\es \"x\""));
        JsonNode payload = new ObjectMapper().readTree(container.getElasticsearchPayload(""));
        assertEquals("C:\\backups\\es \"x\"", payload.path("settings").path("location").asText());
    }

    @Test
    void withoutPathTheConfigurationIsIncomplete() throws Exception {
        ContainerFilesystem container = new ContainerFilesystem(config(""));
        assertNotNull(container.getConfigurationProblem());
        assertTrue(container.getConfigurationProblem().contains("blueberry.orchestrationConfig.filesystem.path"));
    }

    @Test
    void theFactoryFindsTheFilesystemContainerWhateverTheCase() throws Exception {
        ContainerFilesystem container = new ContainerFilesystem(config("/b"));
        ContainerFactory factory = new ContainerFactory();
        Field containers = ContainerFactory.class.getDeclaredField("containers");
        containers.setAccessible(true);
        containers.set(factory, List.of(container));

        assertSame(container, factory.getContainerFromType("filesystem"));
        assertSame(container, factory.getContainerFromType("FILESYSTEM"));
        assertNull(factory.getContainerFromType("gcs"));
    }
}
