package io.camunda.blueberry.connect.cluster;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterFactoryTest {

    static class Fake implements ClusterConnect {
        private final String name;
        private final List<Step> steps;

        Fake(String name, List<Step> steps) {
            this.name = name;
            this.steps = steps;
        }

        public String getName() {
            return name;
        }

        public List<Step> checkAccess() {
            return steps;
        }

        public int stopOptimize(OperationLog log) throws OperationException {
            return 1;
        }

        public void startOptimize(int replicas, OperationLog log) throws OperationException {
        }
    }

    private static BlueberryConfig config(String type) throws Exception {
        BlueberryConfig config = new BlueberryConfig();
        Field field = BlueberryConfig.class.getDeclaredField("clusterType");
        field.setAccessible(true);
        field.set(config, type);
        return config;
    }

    @Test
    void theFactoryGivesTheConfiguredCluster() throws Exception {
        Fake kubernetes = new Fake("kubernetes", List.of());
        Fake docker = new Fake("docker", List.of());

        assertSame(docker, new ClusterFactory(List.of(kubernetes, docker), config("Docker")).getCluster());
        assertSame(kubernetes, new ClusterFactory(List.of(kubernetes, docker), config("kubernetes")).getCluster());
        assertEquals("docker", new ClusterFactory(List.of(kubernetes, docker), config("DOCKER")).getName());
    }

    @Test
    void anUnknownTypeGivesNoCluster() throws Exception {
        ClusterFactory factory = new ClusterFactory(List.of(new Fake("docker", List.of())), config("swarm"));
        assertNull(factory.getCluster());
        assertEquals("swarm", factory.getName());
        assertEquals(List.of("docker"), factory.getSupportedNames());
    }

    @Test
    void pingFailsWhenAVerificationFails() {
        assertTrue(new Fake("x", List.of(ClusterConnect.Step.correct("a", "c"), ClusterConnect.Step.info("i", "c", ""))).ping());
        assertFalse(new Fake("x", List.of(ClusterConnect.Step.correct("a", "c"), ClusterConnect.Step.failed("b", "c", "e", null))).ping());
    }
}
