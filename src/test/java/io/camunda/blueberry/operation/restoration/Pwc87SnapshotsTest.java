package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Pwc87SnapshotsTest {

    private static final RestoreLayout.Repositories REPOSITORIES = new RestoreLayout.Repositories(
            "operaterepository", "operaterepository", "operaterepository", "camunda_zeebe_records_backup");

    private static final List<String> COMPONENT_SNAPSHOTS = List.of(
            "camunda_operate_3_8.7.43_part_1_of_1",
            "camunda_tasklist_3_8.7.43_part_1_of_1",
            "camunda_optimize_3_8.7.29_part_1_of_1");

    @Test
    void findsTheZeebeRecordsSnapshotNamedWithTheBackupIdOnly() throws Exception {
        // This is the name given by the backup of Blueberry
        Map<String, List<String>> repositories = Map.of(
                "operaterepository", COMPONENT_SNAPSHOTS,
                "camunda_zeebe_records_backup", List.of("3", "4"));

        List<Pwc87Snapshots.Planned> planned = Pwc87Snapshots.plan(REPOSITORIES, repositories::get, 3, true);

        Pwc87Snapshots.Planned zeebeRecords = planned.get(planned.size() - 1);
        assertEquals(Pwc87Snapshots.Kind.ZEEBE_RECORDS, zeebeRecords.kind());
        assertEquals("3", zeebeRecords.name());
        assertEquals("camunda_zeebe_records_backup", zeebeRecords.repository());
    }

    @Test
    void findsTheZeebeRecordsSnapshotNamedLikeThePwcRunbook() throws Exception {
        Map<String, List<String>> repositories = Map.of(
                "operaterepository", COMPONENT_SNAPSHOTS,
                "camunda_zeebe_records_backup", List.of("camunda_zeebe_records_backup_3"));

        List<Pwc87Snapshots.Planned> planned = Pwc87Snapshots.plan(REPOSITORIES, repositories::get, 3, true);

        assertEquals("camunda_zeebe_records_backup_3", planned.get(planned.size() - 1).name());
    }

    @Test
    void doesNotTakeTheSnapshotOfAnotherBackup() {
        Map<String, List<String>> repositories = Map.of(
                "operaterepository", COMPONENT_SNAPSHOTS,
                "camunda_zeebe_records_backup", List.of("4", "5"));

        OperationException error = assertThrows(OperationException.class,
                () -> Pwc87Snapshots.plan(REPOSITORIES, repositories::get, 3, true));

        assertEquals(true, error.getMessage().contains("camunda_zeebe_records_backup"));
    }
}
