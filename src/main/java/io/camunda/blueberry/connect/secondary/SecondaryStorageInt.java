package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;

import java.util.List;

/**
 * A secondary storage of the cluster (Elasticsearch, OpenSearch, a database). It is backed up like the other components.
 */
public interface SecondaryStorageInt extends BackupComponentInt {

    /**
     * Check that the storage answers. Does not change anything.
     *
     * @return CONNECTED, NOT_CONNECTED, or NO_IMPLEMENTATION when the storage can't be checked
     */
    default PingStatus ping() {
        return PingStatus.NO_IMPLEMENTATION;
    }

    /**
     * Name of the storage, used by the configuration (blueberry.secondaryStorage) and by the factory
     */
    String getName();

    /**
     * Other names of the storage: "postgresql" for "Postgres". The case and the separators are ignored.
     */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * @return true for a database (PostgreSQL, SQL Server): its restore must be done BEFORE the restore of Zeebe, which reads the
     * exporter position in it. An Elasticsearch restore is validated by a dry run of the restore of Zeebe before it starts.
     */
    default boolean isDatabase() {
        return false;
    }

    /**
     * @return true if Blueberry restores this storage (see {@link #restore(long, OperationLog)})
     */
    default boolean supportsRestore() {
        return false;
    }

    /**
     * Check, without changing anything, that the backup can be restored: the backup exists, is complete.
     *
     * @throws OperationException the backup can't be restored. The message says why.
     */
    default void checkRestorable(long backupId, OperationLog operationLog) throws OperationException {
    }

    /**
     * Restore the storage from the backup. DESTRUCTIVE: the current content is replaced. It is called by the restore
     * of the cluster, when the cluster is in recovery mode.
     *
     * @throws OperationException the restore failed
     */
    default void restore(long backupId, OperationLog operationLog) throws OperationException {
        throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400, "Restore not available",
                "Blueberry can't restore the secondary storage [" + getName() + "]");
    }

    /**
     * Result of {@link #ping()}
     */
    enum PingStatus {
        CONNECTED, NOT_CONNECTED, NO_IMPLEMENTATION
    }
}
