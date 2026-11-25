package io.camunda.blueberry.platform.rule;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.OptimizeConnect;
import io.camunda.blueberry.connect.cluster.ClusterConnect;
import io.camunda.blueberry.connect.cluster.ClusterFactory;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleClusterAccessTest {

    private static class FakeCluster implements ClusterConnect {
        int checks = 0;
        final List<Step> steps;

        FakeCluster(List<Step> steps) {
            this.steps = steps;
        }

        public String getName() {
            return "docker";
        }

        public List<Step> checkAccess() {
            checks++;
            return steps;
        }

        public int stopOptimize(OperationLog log) throws OperationException {
            return 1;
        }

        public void startOptimize(int replicas, OperationLog log) throws OperationException {
        }
    }

    private static ClusterFactory factory(FakeCluster cluster) throws Exception {
        BlueberryConfig config = new BlueberryConfig();
        Field field = BlueberryConfig.class.getDeclaredField("clusterType");
        field.setAccessible(true);
        field.set(config, "docker");
        return new ClusterFactory(List.of(cluster), config);
    }

    private static OptimizeConnect optimize(boolean active) {
        return new OptimizeConnect(new BlueberryConfig(), new RestTemplate(), null) {
            @Override
            public boolean isActive() {
                return active;
            }
        };
    }

    @Test
    void optimizeNotInTheClusterGivesTheTypeAndAnInformation() throws Exception {
        FakeCluster cluster = new FakeCluster(List.of());
        Rule.RuleInfo info = new RuleClusterAccess(factory(cluster), optimize(false)).check();

        assertEquals(Rule.RuleStatus.INFO, info.getStatus());
        assertEquals(2, info.getListVerifications().size());
        assertTrue(info.getListVerifications().get(0).action().contains("Cluster type: docker"));
        assertEquals(Rule.RuleStatus.INFO, info.getListVerifications().get(0).actionStatus());
        assertTrue(info.getListVerifications().get(1).action().contains("Optimize not in the cluster"));
        // the cluster is not called
        assertEquals(0, cluster.checks);
    }

    @Test
    void aFailedVerificationFailsTheRule() throws Exception {
        FakeCluster cluster = new FakeCluster(List.of(ClusterConnect.Step.correct("Connect", "docker version"),
                ClusterConnect.Step.failed("Find the container", "docker inspect", "not found", null)));
        Rule.RuleInfo info = new RuleClusterAccess(factory(cluster), optimize(true)).check();

        assertEquals(Rule.RuleStatus.FAILED, info.getStatus());
        assertEquals(3, info.getListVerifications().size());
    }

    @Test
    void everythingWorks() throws Exception {
        FakeCluster cluster = new FakeCluster(List.of(ClusterConnect.Step.correct("Connect", "docker version")));
        Rule.RuleInfo info = new RuleClusterAccess(factory(cluster), optimize(true)).check();

        assertEquals(Rule.RuleStatus.CORRECT, info.getStatus());
    }
}
