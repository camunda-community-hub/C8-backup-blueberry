package io.camunda.blueberry.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterVersionReaderTest {

    @Test
    void readsTheVersionOfTheChartLabel() {
        assertEquals("12.14.1", ClusterVersionReader.chartVersion("camunda-platform-12.14.1"));
        assertEquals("12.1.0", ClusterVersionReader.chartVersion("camunda-platform-12.1.0"));
        assertEquals("15.0.0", ClusterVersionReader.chartVersion("camunda-platform-15.0.0-alpha1"));
        assertEquals("", ClusterVersionReader.chartVersion("camunda-platform"));
        assertEquals("", ClusterVersionReader.chartVersion(""));
        assertEquals("", ClusterVersionReader.chartVersion(null));
    }

    @Test
    void comparesTheVersionsNumberByNumber() {
        String minimum = ClusterVersionReader.MIN_HELM_CHART_FOR_ZEEBE_RESTORE;
        // 12.14 is after 12.1: a text comparison would be wrong
        assertTrue(ClusterVersionReader.compareVersions("12.14.1", minimum) > 0);
        assertEquals(0, ClusterVersionReader.compareVersions("12.1.0", minimum));
        assertEquals(0, ClusterVersionReader.compareVersions("12.1", minimum));
        assertTrue(ClusterVersionReader.compareVersions("12.0.9", minimum) < 0);
        assertTrue(ClusterVersionReader.compareVersions("11.9.9", minimum) < 0);
        assertTrue(ClusterVersionReader.compareVersions("13.0.0", minimum) > 0);
    }
}
