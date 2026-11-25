package io.camunda.blueberry.connect.cluster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerConnectTest {

    @Test
    void theContainersAreReadInTheOutputOfDockerPs() {
        List<DockerConnect.DockerContainer> containers = DockerConnect.parseContainers("c-8-10-optimize-1|running\r\nc-8-10-optimize-init-1|exited\n\n");

        assertEquals(2, containers.size());
        assertEquals("c-8-10-optimize-1", containers.get(0).name());
        assertTrue(containers.get(0).running());
        assertEquals("c-8-10-optimize-init-1", containers.get(1).name());
        assertFalse(containers.get(1).running());
    }

    @Test
    void noContainer() {
        assertTrue(DockerConnect.parseContainers("").isEmpty());
    }
}
