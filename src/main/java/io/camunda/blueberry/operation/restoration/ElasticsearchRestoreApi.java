package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;

import java.util.List;

/**
 * Elasticsearch calls for the 8.7 restore procedure. Snapshot restore sends no index filter.
 */
public interface ElasticsearchRestoreApi {

    List<String> listIndices() throws OperationException;

    void deleteIndex(String index) throws OperationException;

    List<String> listSnapshotNames(String repository) throws OperationException;

    void restoreSnapshot(String repository, String snapshot) throws OperationException;

    List<String> listIndexTemplateNames() throws OperationException;
}
