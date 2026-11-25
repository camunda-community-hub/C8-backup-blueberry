package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import io.camunda.blueberry.platform.componentconfig.TestOrchestration;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecondaryStoragePingTest {

    private static void set(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private PostgresConnect postgres(int port) throws Exception {
        PostgresConnect connect = new PostgresConnect(TestOrchestration.build(Map.of(OrchestrationParameter.SECONDARY_STORAGE.name(), "postgres"), null));
        set(connect, "host", "localhost");
        set(connect, "port", port);
        return connect;
    }

    @Test
    void postgresConnectedWhenThePortAnswers() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            assertEquals(SecondaryStorageInt.PingStatus.CONNECTED, postgres(server.getLocalPort()).ping());
        }
    }

    @Test
    void postgresNotConnectedWhenNothingListens() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }
        assertEquals(SecondaryStorageInt.PingStatus.NOT_CONNECTED, postgres(port).ping());
    }
}
