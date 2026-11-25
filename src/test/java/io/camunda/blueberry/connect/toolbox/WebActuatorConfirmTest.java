package io.camunda.blueberry.connect.toolbox;

import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.connect.ActuatorBackupStatusResponse;
import io.camunda.blueberry.operation.OperationLog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebActuatorConfirmTest {

    private static final BackupComponentInt.COMPONENT OPTIMIZE = BackupComponentInt.COMPONENT.OPTIMIZE;

    private static WebActuator webActuator(RestTemplate restTemplate) {
        WebActuator webActuator = new WebActuator(restTemplate);
        // the tests wait less
        webActuator.confirmTimeoutMillis = 30;
        webActuator.confirmPollMillis = 10;
        return webActuator;
    }

    private static BackupComponentInt.BackupOperation started() {
        BackupComponentInt.BackupOperation started = new BackupComponentInt.BackupOperation();
        started.status = 202;
        return started;
    }

    private static ActuatorBackupStatusResponse status(String state) throws Exception {
        ActuatorBackupStatusResponse response = new ActuatorBackupStatusResponse();
        Field field = ActuatorBackupStatusResponse.class.getDeclaredField("state");
        field.setAccessible(true);
        field.set(response, state);
        return response;
    }

    @Test
    void theComponentKnowsTheBackup() throws Exception {
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType, Object... uriVariables) {
                try {
                    return (ResponseEntity<T>) ResponseEntity.ok(status("IN_PROGRESS"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        BackupComponentInt.BackupOperation started = started();

        assertSame(started, webActuator(restTemplate).confirmBackupCreated(OPTIMIZE, 12L, "http://optimize:8092", new OperationLog(), started));
    }

    @Test
    void aBackupUnknownAfterTheStartIsNotCreated_TheJobMustStopHere() {
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType, Object... uriVariables) {
                throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
            }
        };

        BackupComponentInt.BackupOperation result = webActuator(restTemplate)
                .confirmBackupCreated(OPTIMIZE, 12L, "http://optimize:8092", new OperationLog(), started());

        assertEquals(500, result.status);
        assertTrue(result.title.contains("was not created by OPTIMIZE"), result.title);
        assertTrue(result.message.contains("restart OPTIMIZE"), result.message);
    }

    @Test
    void aBackupKnownAfterAFewSecondsIsAccepted() throws Exception {
        int[] calls = {0};
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType, Object... uriVariables) {
                calls[0]++;
                if (calls[0] < 3) {
                    throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
                }
                try {
                    return (ResponseEntity<T>) ResponseEntity.ok(status("IN_PROGRESS"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        BackupComponentInt.BackupOperation started = started();

        assertSame(started, webActuator(restTemplate).confirmBackupCreated(OPTIMIZE, 12L, "http://optimize:8092", new OperationLog(), started));
    }

    @Test
    void aFailedBackupStopsTheJob() throws Exception {
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ResponseEntity<T> getForEntity(String url, Class<T> responseType, Object... uriVariables) {
                try {
                    return (ResponseEntity<T>) ResponseEntity.ok(status("FAILED"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        BackupComponentInt.BackupOperation result = webActuator(restTemplate)
                .confirmBackupCreated(OPTIMIZE, 12L, "http://optimize:8092", new OperationLog(), started());

        assertEquals(500, result.status);
    }
}

