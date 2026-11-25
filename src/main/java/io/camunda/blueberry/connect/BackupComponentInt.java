package io.camunda.blueberry.connect;

import io.camunda.blueberry.exception.BackupException;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;

import java.util.ArrayList;
import java.util.List;

public interface BackupComponentInt {

    /**
     * True is the component is active in the cluster
     *
     * @return
     */
    boolean isActive();

    COMPONENT getComponent();

    BackupOperation backup(Long backupId, OperationLog operationLog) throws BackupException;

    BackupOperation waitBackup(Long backupId, OperationLog operationLog) throws BackupException;

    List<BackupInfo> getListBackups() throws OperationException;

    String getUrlListBackup();

    /**
     * The components Blueberry backs up and restores. SECONDARYSTORAGE is the secondary storage of the cluster (Elasticsearch, OpenSearch,
     * a database): its implementation is given by the SecondaryStorageFactory. Operate and Tasklist are no longer separate
     * applications since 8.10: their data is in the secondary storage, backed up with Zeebe.
     */
    enum COMPONENT {OPTIMIZE, ZEEBE, SECONDARYSTORAGE}

    class BackupOperation {
        public List<String> listSnapshots = new ArrayList<>();
        public int status;
        public String title;
        public String message;

        public boolean isOk() {
            return status == 200 || status == 202; // Accept both 200 (Operate, Tasklist) and 202 (Optimize)
        }
    }

}
