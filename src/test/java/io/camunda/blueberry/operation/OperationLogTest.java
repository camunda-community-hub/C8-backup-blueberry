package io.camunda.blueberry.operation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperationLogTest {

    private static OperationLog.Message message(OperationLog.Type type, String content) {
        OperationLog.Message message = new OperationLog.Message();
        message.type = type;
        message.step = 1;
        message.content.add(content);
        return message;
    }

    @Test
    void theHighestTypeIsKept() {
        assertEquals(OperationLog.Type.ERROR, OperationLog.highestType(OperationLog.Type.ERROR, OperationLog.Type.WARNING));
        assertEquals(OperationLog.Type.ERROR, OperationLog.highestType(OperationLog.Type.WARNING, OperationLog.Type.ERROR));
        assertEquals(OperationLog.Type.WARNING, OperationLog.highestType(OperationLog.Type.WARNING, OperationLog.Type.INFO));
        assertEquals(OperationLog.Type.WARNING, OperationLog.highestType(OperationLog.Type.INFO, OperationLog.Type.WARNING));
        assertEquals(OperationLog.Type.INFO, OperationLog.highestType(OperationLog.Type.INFO, OperationLog.Type.INFO));
        assertEquals(OperationLog.Type.INFO, OperationLog.highestType(null, OperationLog.Type.INFO));
        assertEquals(OperationLog.Type.ERROR, OperationLog.highestType(OperationLog.Type.ERROR, null));
    }

    @Test
    void messagesOfTheSameStepAndComponentAreMergedWithTheHighestType() {
        OperationLog log = new OperationLog();
        log.mergeMessage(message(OperationLog.Type.INFO, "one"));
        log.mergeMessage(message(OperationLog.Type.ERROR, "two"));
        log.mergeMessage(message(OperationLog.Type.WARNING, "three"));

        assertEquals(1, log.getMessages().size());
        OperationLog.Message merged = log.getMessages().get(0);
        assertEquals(java.util.List.of("one", "two", "three"), merged.content);
        // INFO, then ERROR, then WARNING: the merged message stays ERROR
        assertEquals(OperationLog.Type.ERROR, merged.type);
    }
}
