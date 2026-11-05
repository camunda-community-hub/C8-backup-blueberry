package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snapshot names from the PwC Camunda 8.7 runbook, section 3 step 6.
 * Part counts come from the snapshot names. The runbook example (6 and 2) is not hardcoded.
 */
public final class Pwc87Snapshots {

    public enum Kind {OPERATE, TASKLIST, OPTIMIZE, ZEEBE_RECORDS}

    public record Planned(Kind kind, String name, int part, int partCount) {
    }

    private static final Pattern PART = Pattern.compile(
            "^camunda_(operate|tasklist|optimize)_(\\d+)_([^_]+)_part_(\\d+)_of_(\\d+)$");
    private static final Pattern ZEEBE_RECORDS = Pattern.compile("^camunda_zeebe_records_backup_(\\d+)$");

    private Pwc87Snapshots() {
    }

    public static List<Planned> plan(List<String> snapshotNames, long backupId, boolean requireOptimize)
            throws OperationException {
        List<Planned> operate = new ArrayList<>();
        List<Planned> tasklist = new ArrayList<>();
        List<Planned> optimize = new ArrayList<>();
        Planned zeebeRecords = null;

        for (String name : snapshotNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Matcher part = PART.matcher(name);
            if (part.matches()) {
                if (Long.parseLong(part.group(2)) != backupId) {
                    continue;
                }
                Kind kind = switch (part.group(1)) {
                    case "operate" -> Kind.OPERATE;
                    case "tasklist" -> Kind.TASKLIST;
                    case "optimize" -> Kind.OPTIMIZE;
                    default -> null;
                };
                if (kind == null) {
                    continue;
                }
                Planned planned = new Planned(kind, name, Integer.parseInt(part.group(4)), Integer.parseInt(part.group(5)));
                switch (kind) {
                    case OPERATE -> operate.add(planned);
                    case TASKLIST -> tasklist.add(planned);
                    case OPTIMIZE -> optimize.add(planned);
                    case ZEEBE_RECORDS -> {
                    }
                }
                continue;
            }
            Matcher records = ZEEBE_RECORDS.matcher(name);
            if (records.matches() && Long.parseLong(records.group(1)) == backupId) {
                zeebeRecords = new Planned(Kind.ZEEBE_RECORDS, name, 1, 1);
            }
        }

        requireComplete("Operate", operate);
        requireComplete("Tasklist", tasklist);
        if (requireOptimize) {
            requireComplete("Optimize", optimize);
        }
        if (zeebeRecords == null) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Missing snapshot",
                    "Snapshot camunda_zeebe_records_backup_" + backupId + " was not found.");
        }

        List<Planned> ordered = new ArrayList<>();
        ordered.addAll(sorted(operate));
        ordered.addAll(sorted(tasklist));
        if (requireOptimize) {
            ordered.addAll(sorted(optimize));
        }
        ordered.add(zeebeRecords);
        return ordered;
    }

    private static List<Planned> sorted(List<Planned> parts) {
        return parts.stream().sorted(Comparator.comparingInt(Planned::part)).toList();
    }

    private static void requireComplete(String component, List<Planned> parts) throws OperationException {
        if (parts.isEmpty()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Missing snapshot",
                    "No " + component + " snapshots were found for this backup ID.");
        }
        int expected = parts.get(0).partCount();
        for (Planned part : parts) {
            if (part.partCount() != expected) {
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                        "Inconsistent snapshots",
                        component + " snapshots declare different part counts.");
            }
        }
        if (parts.size() != expected) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Incomplete snapshots",
                    component + " expected " + expected + " snapshot parts and found " + parts.size() + ".");
        }
        boolean[] seen = new boolean[expected + 1];
        for (Planned part : parts) {
            if (part.part() < 1 || part.part() > expected || seen[part.part()]) {
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                        "Incomplete snapshots",
                        component + " snapshot parts are not a complete 1.." + expected + " set.");
            }
            seen[part.part()] = true;
        }
    }
}
