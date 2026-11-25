package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.exception.OperationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchSnapshotPlanTest {

    private static final List<String> ALL = List.of(
            "camunda_webapps_7_8.10.0_part_2_of_3", "camunda_webapps_7_8.10.0_part_1_of_3", "camunda_webapps_7_8.10.0_part_3_of_3",
            "camunda_optimize_7_8.10.0_part_1_of_2", "camunda_optimize_7_8.10.0_part_2_of_2",
            "camunda_zeebe_records_backup_7",
            // another backup: never restored
            "camunda_webapps_70_8.10.0_part_1_of_1", "camunda_webapps_8_8.10.0_part_1_of_1", "8");

    @Test
    void theSnapshotsOfTheBackupAreInTheOrderOfTheRestore() throws Exception {
        List<ElasticsearchSnapshotPlan.Planned> plan = ElasticsearchSnapshotPlan.plan(7, Map.of("repo", ALL));

        assertEquals(List.of(
                "camunda_webapps_7_8.10.0_part_1_of_3", "camunda_webapps_7_8.10.0_part_2_of_3", "camunda_webapps_7_8.10.0_part_3_of_3",
                "camunda_optimize_7_8.10.0_part_1_of_2", "camunda_optimize_7_8.10.0_part_2_of_2",
                "camunda_zeebe_records_backup_7"), plan.stream().map(ElasticsearchSnapshotPlan.Planned::name).toList());
        assertEquals(ElasticsearchSnapshotPlan.Kind.WEBAPPS, plan.get(0).kind());
        assertEquals(ElasticsearchSnapshotPlan.Kind.ZEEBE_RECORDS, plan.get(5).kind());
    }

    @Test
    void theSnapshotOfTheZeebeRecordsNamedWithTheBackupIdOnlyIsAccepted() throws Exception {
        // Blueberry names the snapshot of the Zeebe records with the backup ID
        List<ElasticsearchSnapshotPlan.Planned> plan = ElasticsearchSnapshotPlan.plan(8,
                Map.of("records", List.of("8", "7"), "webapps", List.of("camunda_webapps_8_8.10.0_part_1_of_1")));
        assertEquals("8", plan.get(1).name());
        assertEquals("records", plan.get(1).repository());
        assertEquals("webapps", plan.get(0).repository());
    }

    @Test
    void optimizeIsOptional() throws Exception {
        List<ElasticsearchSnapshotPlan.Planned> plan = ElasticsearchSnapshotPlan.plan(8, Map.of("repo", ALL));
        assertEquals(2, plan.size());
        assertFalse(plan.stream().anyMatch(p -> p.kind() == ElasticsearchSnapshotPlan.Kind.OPTIMIZE));
    }

    @Test
    void aMissingPartIsAnError() {
        List<String> names = List.of("camunda_webapps_7_8.10.0_part_1_of_3", "camunda_webapps_7_8.10.0_part_3_of_3", "camunda_zeebe_records_backup_7");
        OperationException e = assertThrows(OperationException.class, () -> ElasticsearchSnapshotPlan.plan(7, Map.of("repo", names)));
        assertTrue(e.getMessage().contains("[2]"), e.getMessage());
    }

    @Test
    void theZeebeRecordsAreOptionalWithoutOptimize() throws Exception {
        // the Zeebe records are in the backup only for Optimize
        List<ElasticsearchSnapshotPlan.Planned> plan = ElasticsearchSnapshotPlan.plan(7,
                Map.of("repo", List.of("camunda_webapps_7_8.10.0_part_1_of_1")));

        assertEquals(List.of("camunda_webapps_7_8.10.0_part_1_of_1"), plan.stream().map(ElasticsearchSnapshotPlan.Planned::name).toList());
    }

    @Test
    void theZeebeRecordsAreRequiredWithOptimize() {
        assertThrows(OperationException.class, () -> ElasticsearchSnapshotPlan.plan(7,
                Map.of("repo", List.of("camunda_webapps_7_8.10.0_part_1_of_1", "camunda_optimize_7_8.10.0_part_1_of_1"))));
    }

    @Test
    void theWebappsAreRequired() {
        assertThrows(OperationException.class, () -> ElasticsearchSnapshotPlan.plan(7, Map.of("repo", List.of("camunda_zeebe_records_backup_7"))));
    }

    @Test
    void camundaIndicesAreDeletedButNotTheSystemIndices() {
        assertTrue(ElasticsearchSnapshotPlan.isCamundaIndex("operate-list-view-8.3.0_"));
        assertTrue(ElasticsearchSnapshotPlan.isCamundaIndex("zeebe-record_job_8.10.0_2026-11-05"));
        assertTrue(ElasticsearchSnapshotPlan.isCamundaIndex("tasklist-task-8.8.0_"));
        assertTrue(ElasticsearchSnapshotPlan.isCamundaIndex("optimize-alert_v4"));
        assertTrue(ElasticsearchSnapshotPlan.isCamundaIndex("camunda-authorization-8.8.0_"));
        assertFalse(ElasticsearchSnapshotPlan.isCamundaIndex(".kibana_1"));
        assertFalse(ElasticsearchSnapshotPlan.isCamundaIndex(".security-7"));
        assertFalse(ElasticsearchSnapshotPlan.isCamundaIndex("my-own-index"));
        assertFalse(ElasticsearchSnapshotPlan.isCamundaIndex(""));
    }
}
