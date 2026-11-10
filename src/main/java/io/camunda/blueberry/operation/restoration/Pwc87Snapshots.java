package io.camunda.blueberry.operation.restoration;

import io.camunda.blueberry.exception.OperationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snapshot names from the PwC Camunda 8.7 runbook, section 3 step 6.
 * Part counts come from the snapshot names. The runbook example (6 and 2) is not hardcoded.
 * <p>
 * Each component has its own repository, the one used by the backup (BlueberryConfig):
 * the snapshots of a component are searched in its repository.
 */
public final class Pwc87Snapshots {

    public enum Kind {OPERATE, TASKLIST, OPTIMIZE, ZEEBE_RECORDS}

    /**
     * A snapshot to restore, with the repository holding it
     */
    public record Planned(Kind kind, String repository, String name, int part, int partCount) {
    }

    /**
     * Give the names of the snapshots of a repository
     */
    @FunctionalInterface
    public interface SnapshotSource {
        List<String> snapshotNames(String repository) throws OperationException;
    }

    private static final Pattern PART = Pattern.compile(
            "^camunda_(operate|tasklist|optimize)_(\\d+)_([^_]+)_part_(\\d+)_of_(\\d+)$");
    /**
     * The snapshot of the Zeebe records. Blueberry names it with the backup ID only (3), the PwC runbook names it
     * camunda_zeebe_records_backup_3: both are accepted.
     */
    private static final Pattern ZEEBE_RECORDS = Pattern.compile("^(?:camunda_zeebe_records_backup_)?(\\d+)$");

    private Pwc87Snapshots() {
    }

    public static List<Planned> plan(RestoreLayout.Repositories repositories, SnapshotSource source,
                                     long backupId, boolean requireOptimize) throws OperationException {
        // One call per repository: components may share the same repository
        Map<String, List<String>> namesPerRepository = new HashMap<>();

        List<Planned> operate = parts(Kind.OPERATE, "operate", repositories.operate(), source, namesPerRepository, backupId);
        List<Planned> tasklist = parts(Kind.TASKLIST, "tasklist", repositories.tasklist(), source, namesPerRepository, backupId);
        List<Planned> optimize = requireOptimize
                ? parts(Kind.OPTIMIZE, "optimize", repositories.optimize(), source, namesPerRepository, backupId)
                : List.of();

        requireComplete("Operate", repositories.operate(), operate);
        requireComplete("Tasklist", repositories.tasklist(), tasklist);
        if (requireOptimize) {
            requireComplete("Optimize", repositories.optimize(), optimize);
        }

        Planned zeebeRecords = null;
        for (String name : names(repositories.zeebeRecords(), "Zeebe records", source, namesPerRepository)) {
            Matcher records = ZEEBE_RECORDS.matcher(name == null ? "" : name);
            if (records.matches() && Long.parseLong(records.group(1)) == backupId) {
                zeebeRecords = new Planned(Kind.ZEEBE_RECORDS, repositories.zeebeRecords(), name, 1, 1);
            }
        }
        if (zeebeRecords == null) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Missing snapshot",
                    "Snapshot [" + backupId + "] (or camunda_zeebe_records_backup_" + backupId
                            + ") was not found in the repository [" + repositories.zeebeRecords() + "] of the Zeebe records.");
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

    private static List<String> names(String repository, String component, SnapshotSource source,
                                      Map<String, List<String>> cache) throws OperationException {
        if (repository == null || repository.isBlank()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Repository not defined",
                    "The repository of " + component + " is not defined in the blueberry configuration.");
        }
        List<String> names = cache.get(repository);
        if (names == null) {
            names = source.snapshotNames(repository);
            cache.put(repository, names);
        }
        return names;
    }

    private static List<Planned> parts(Kind kind, String component, String repository, SnapshotSource source,
                                       Map<String, List<String>> cache, long backupId) throws OperationException {
        List<Planned> planned = new ArrayList<>();
        for (String name : names(repository, component, source, cache)) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Matcher part = PART.matcher(name);
            if (part.matches() && part.group(1).equals(component) && Long.parseLong(part.group(2)) == backupId) {
                planned.add(new Planned(kind, repository, name, Integer.parseInt(part.group(4)), Integer.parseInt(part.group(5))));
            }
        }
        return planned;
    }

    private static List<Planned> sorted(List<Planned> parts) {
        return parts.stream().sorted(Comparator.comparingInt(Planned::part)).toList();
    }

    private static void requireComplete(String component, String repository, List<Planned> parts) throws OperationException {
        if (parts.isEmpty()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.ELASTICSEARCH_CLIENT, 400,
                    "Missing snapshot",
                    "No " + component + " snapshots were found for this backup ID in the repository [" + repository + "].");
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
