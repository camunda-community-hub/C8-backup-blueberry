package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;

/**
 * Force Zeebe to create its Elasticsearch index templates.
 * The Zeebe exporter creates them when it exports its first records: the seeder produces these records.
 */
public interface ZeebeTemplateSeeder {

    /**
     * Run a small process in Zeebe, in order to produce records
     */
    void seed(OperationLog log) throws OperationException;
}
