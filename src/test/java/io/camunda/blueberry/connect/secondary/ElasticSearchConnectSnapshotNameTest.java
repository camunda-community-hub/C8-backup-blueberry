package io.camunda.blueberry.connect.secondary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ElasticSearchConnectSnapshotNameTest {

    @Test
    void theSnapshotOfTheZeebeRecordsIsNamedAsInTheDocumentation() {
        assertEquals("camunda_zeebe_records_backup_202611240020", ElasticSearchConnect.zeebeRecordSnapshotName(202611240020L));
    }

    @Test
    void theBackupIdIsReadInTheNameOfTheDocumentationAndInTheOlderName() {
        assertEquals(202611240020L, ElasticSearchConnect.zeebeRecordBackupId("camunda_zeebe_records_backup_202611240020"));
        // the older name of Blueberry: the backup id only
        assertEquals(12L, ElasticSearchConnect.zeebeRecordBackupId("12"));
    }

    @Test
    void theOtherSnapshotsOfTheRepositoryAreNotBackupsOfTheZeebeRecords() {
        assertNull(ElasticSearchConnect.zeebeRecordBackupId("camunda_webapps_1748937221_8.8.0_part_1_of_5"));
        assertNull(ElasticSearchConnect.zeebeRecordBackupId("camunda_optimize_1748937221_8.8.0_part_1_of_2"));
        assertNull(ElasticSearchConnect.zeebeRecordBackupId(null));
        assertNull(ElasticSearchConnect.zeebeRecordBackupId(""));
    }

    @Test
    void aBackupOfTheSecondaryStorageIsTheHistoryBackupOrTheZeebeRecords() {
        // the history backup alone is a backup: the snapshot of the Zeebe records exists only for Optimize
        assertEquals(1748937221L, ElasticSearchConnect.secondaryStorageBackupId("camunda_webapps_1748937221_8.8.0_part_1_of_5"));
        assertEquals(1748937221L, ElasticSearchConnect.secondaryStorageBackupId("camunda_zeebe_records_backup_1748937221"));
        // Optimize has its own backup
        assertNull(ElasticSearchConnect.secondaryStorageBackupId("camunda_optimize_1748937221_8.8.0_part_1_of_2"));
        assertNull(ElasticSearchConnect.secondaryStorageBackupId(null));
    }
}
