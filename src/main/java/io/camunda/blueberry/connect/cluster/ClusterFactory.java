package io.camunda.blueberry.connect.cluster;

import io.camunda.blueberry.config.BlueberryConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Gives the {@link ClusterConnect} chosen by blueberry.cluster.type ("kubernetes" or "docker").
 */
@Component
public class ClusterFactory {

    private final List<ClusterConnect> clusters;
    private final BlueberryConfig blueberryConfig;

    public ClusterFactory(List<ClusterConnect> clusters, BlueberryConfig blueberryConfig) {
        this.clusters = clusters;
        this.blueberryConfig = blueberryConfig;
    }

    /**
     * @return the type in the configuration (blueberry.cluster.type), lower case
     */
    public String getConfiguredType() {
        String type = blueberryConfig.getClusterType();
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @return the object for the configured type, null if the type is unknown
     */
    public ClusterConnect getCluster() {
        String type = getConfiguredType();
        return clusters.stream().filter(c -> c.getName().equalsIgnoreCase(type)).findFirst().orElse(null);
    }

    /**
     * @return the name of the cluster (kubernetes, docker), or the configured value when it is not supported
     */
    public String getName() {
        ClusterConnect cluster = getCluster();
        return cluster == null ? getConfiguredType() : cluster.getName();
    }

    public List<String> getSupportedNames() {
        return clusters.stream().map(ClusterConnect::getName).toList();
    }
}
