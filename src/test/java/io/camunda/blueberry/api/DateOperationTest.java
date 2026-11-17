package io.camunda.blueberry.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The backup time is a LocalDateTime in UTC. The REST API subtracts the timezone offset of the browser
 * (JavaScript getTimezoneOffset: 480 for PST).
 */
class DateOperationTest {

    @Test
    void backupTakenAt1430PstIsReturnedAt1430() {
        // 14:30 PST (UTC-8) is 22:30 UTC
        LocalDateTime utc = Instant.parse("2026-11-09T22:30:00Z").atZone(ZoneOffset.UTC).toLocalDateTime();
        assertEquals("2026-11-09 14:30", DateOperation.dateTimeToHumanString(utc, 480));
    }

    @Test
    void utcBrowserSeesTheUtcTime() {
        LocalDateTime utc = LocalDateTime.of(2026, 11, 9, 22, 30);
        assertEquals("2026-11-09 22:30", DateOperation.dateTimeToHumanString(utc, 0));
    }
}
