package io.camunda.blueberry.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnapshotRepositoryErrorTest {

    @Test
    void optimizeSaysTheRepositoryDoesNotExist() {
        String error = "500 on GET request for \"http://localhost:8092/actuator/backups\": \"{\"errorCode\":\"serverError\","
                + "\"errorMessage\":\"An internal server error occurred.\",\"detailedMessage\":\"No repository with name [camunda] could be found.\"}\"";

        assertEquals("camunda", SnapshotRepositoryError.detect(error));
    }

    @Test
    void elasticsearchSaysTheRepositoryDoesNotExist() {
        String error = "404 Not Found on GET request for \"http://localhost:9200/_snapshot/camunda/_all\": \"{\"error\":{\"root_cause\":"
                + "[{\"type\":\"repository_missing_exception\",\"reason\":\"[camunda] missing\"}],\"type\":\"repository_missing_exception\","
                + "\"reason\":\"[camunda] missing\"},\"status\":404}\"";

        assertEquals("camunda", SnapshotRepositoryError.detect(error));
    }

    @Test
    void otherErrorsAreNotDetected() {
        assertNull(SnapshotRepositoryError.detect("Connection refused"));
        assertNull(SnapshotRepositoryError.detect(null));
    }

    @Test
    void theMessageAsksToConfigureThePlatform() {
        assertEquals("The snapshot repository [camunda] does not exist in Elasticsearch: Please configure your platform (see Configuration tab)",
                SnapshotRepositoryError.message("camunda"));
    }
}
