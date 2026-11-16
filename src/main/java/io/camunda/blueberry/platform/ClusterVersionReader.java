package io.camunda.blueberry.platform;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.KubernetesClientProvider;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read the version of the Helm chart and the version of Camunda, in the Zeebe StatefulSet of the Helm release:
 * <ul>
 *   <li>label helm.sh/chart: camunda-platform-12.14.1</li>
 *   <li>label app.kubernetes.io/version: 8.7.43 (or the tag of the image)</li>
 * </ul>
 */
@Component
public class ClusterVersionReader {

    /**
     * Charts before this version ignore ZEEBE_RESTORE: the restore of Zeebe does not work
     */
    public static final String MIN_HELM_CHART_FOR_ZEEBE_RESTORE = "12.1.0";

    /**
     * @param workload       the StatefulSet read
     * @param chartLabel     value of the label helm.sh/chart, like camunda-platform-12.14.1
     * @param chartVersion   version of the chart, like 12.14.1. Empty if the label is not readable
     * @param camundaVersion version of Camunda, like 8.7.43. Empty if not found
     */
    public record Versions(String workload, String chartLabel, String chartVersion, String camundaVersion) {
    }

    private static final Pattern CHART_VERSION = Pattern.compile("^.*?-(\\d+(?:\\.\\d+)*)(?:[-+].*)?$");
    private static final Pattern IMAGE_TAG = Pattern.compile("^.*:(\\d+(?:\\.\\d+)*)$");

    private final KubernetesClientProvider kubernetesClientProvider;
    private final BlueberryConfig blueberryConfig;

    public ClusterVersionReader(KubernetesClientProvider kubernetesClientProvider, BlueberryConfig blueberryConfig) {
        this.kubernetesClientProvider = kubernetesClientProvider;
        this.blueberryConfig = blueberryConfig;
    }

    /**
     * @throws Exception if Kubernetes is not reachable, or the StatefulSet of Zeebe does not exist
     */
    public Versions read() throws Exception {
        String namespace = blueberryConfig.getNamespace();
        String name = blueberryConfig.getRestoreHelmRelease() + "-zeebe";
        StatefulSet statefulSet = kubernetesClientProvider.client().apps().statefulSets()
                .inNamespace(namespace).withName(name).get();
        if (statefulSet == null) {
            throw new IllegalStateException("The StatefulSet " + name + " does not exist in the namespace " + namespace
                    + ". Check blueberry.restore.helmRelease (the release is the prefix of the workloads)");
        }
        Map<String, String> labels = statefulSet.getMetadata().getLabels();
        String chartLabel = labels == null ? "" : labels.getOrDefault("helm.sh/chart", "");
        String camundaVersion = labels == null ? "" : labels.getOrDefault("app.kubernetes.io/version", "");
        if (camundaVersion.isBlank()) {
            camundaVersion = statefulSet.getSpec().getTemplate().getSpec().getContainers().stream()
                    .map(container -> imageTag(container.getImage()))
                    .filter(tag -> !tag.isBlank())
                    .findFirst().orElse("");
        }
        return new Versions(name, chartLabel, chartVersion(chartLabel), camundaVersion);
    }

    /**
     * camunda-platform-12.14.1 gives 12.14.1. Empty if there is no version
     */
    public static String chartVersion(String chartLabel) {
        if (chartLabel == null) {
            return "";
        }
        Matcher matcher = CHART_VERSION.matcher(chartLabel);
        return matcher.matches() ? matcher.group(1) : "";
    }

    private static String imageTag(String image) {
        if (image == null) {
            return "";
        }
        Matcher matcher = IMAGE_TAG.matcher(image);
        return matcher.matches() ? matcher.group(1) : "";
    }

    /**
     * Compare two versions, number by number: 12.14.1 is after 12.1.0, and 12.1 is the same as 12.1.0
     *
     * @return negative if a is before b, 0 if equal, positive if a is after b
     */
    public static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            int numberA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int numberB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (numberA != numberB) {
                return Integer.compare(numberA, numberB);
            }
        }
        return 0;
    }
}
