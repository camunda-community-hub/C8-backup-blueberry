package io.camunda.blueberry.connect.secondary;

import java.lang.reflect.Field;

/**
 * Set a private field (the values of the configuration are injected by Spring in production)
 */
final class TestFields {
    private TestFields() {
    }

    static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
