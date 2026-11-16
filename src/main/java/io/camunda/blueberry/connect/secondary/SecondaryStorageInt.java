package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.connect.BackupComponentInt;

/**
 * A secondary storage of the cluster (Elasticsearch, OpenSearch, a database). It is backed up like the other components.
 */
public interface SecondaryStorageInt extends BackupComponentInt {

    /**
     * Name of the storage, used by the configuration (blueberry.secondaryStorage) and by the factory
     */
    String getName();
}
