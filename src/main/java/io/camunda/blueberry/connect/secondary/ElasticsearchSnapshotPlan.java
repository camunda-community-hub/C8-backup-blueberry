package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.exception.OperationException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Elasticsearch / OpenSearch snapshots to restore for a backup (Camunda 8.8 and over).
 * <p>
 * The names, for the backup ID 1748937221 of the version 8.8.0 (documentation of the restore):
 * <pre>
 * camunda_webapps_1748937221_8.8.0_part_1_of_5 ... part_5_of_5   Operate and Tasklist (the history backup). Required
 * camunda_optimize_1748937221_8.8.0_part_1_of_2 ... part_2_of_2  Optimize. Optional (Optimize may not be deployed)
 * camunda_zeebe_records_backup_1748937221                       The Zeebe records. Only for Optimize (Optimize reads them):
 *                                                               required when the backup has the snapshots of Optimize, else
 *                                                               optional. Older Blueberry versions named it with the backup ID
 *                                                               only (1748937221): both are accepted
 * </pre>
 * Each snapshot is restored on its own, one after the other.
 */
public final class ElasticsearchSnapshotPlan {

    private ElasticsearchSnapshotPlan() {
    }

    /**
     * @param backupId           the backup
     * @param namesPerRepository the names of the snapshots, for each repository
     * @return the snapshots to restore, in the order of the restore
     * @throws OperationException a required snapshot is missing, or a part of a snapshot is missing
     */
    public static List<Planned> plan(long backupId, Map<String, List<String>> namesPerRepository) throws OperationException {
        Pattern webapps = partPattern("camunda_webapps_", backupId);
        Pattern optimize = partPattern("camunda_optimize_", backupId);
        Pattern zeebeRecords = Pattern.compile("^(?:camunda_zeebe_records_backup_)?" + backupId + "$");

        List<Planned> webappsParts = new ArrayList<>();
        List<Planned> optimizeParts = new ArrayList<>();
        List<Planned> records = new ArrayList<>();
        for (Map.Entry<String, List<String>> repository : namesPerRepository.entrySet()) {
            for (String name : repository.getValue()) {
                if (name == null) {
                    continue;
                }
                Matcher matcher = webapps.matcher(name);
                if (matcher.matches()) {
                    webappsParts.add(new Planned(Kind.WEBAPPS, repository.getKey(), name,
                            Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
                    continue;
                }
                matcher = optimize.matcher(name);
                if (matcher.matches()) {
                    optimizeParts.add(new Planned(Kind.OPTIMIZE, repository.getKey(), name,
                            Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
                    continue;
                }
                if (zeebeRecords.matcher(name).matches()) {
                    records.add(new Planned(Kind.ZEEBE_RECORDS, repository.getKey(), name, 1, 1));
                }
            }
        }

        if (webappsParts.isEmpty()) {
            throw missing("No snapshot camunda_webapps_" + backupId + "_*_part_k_of_n was found (Operate and Tasklist). "
                    + "It is the history backup: POST /v2/backups/history. Searched in the repositories "
                    + namesPerRepository.keySet() + ".");
        }
        // The Zeebe records are exported in Elasticsearch for Optimize: they are in the backup only when Optimize is active
        if (records.isEmpty() && !optimizeParts.isEmpty()) {
            throw missing("The backup has the snapshots of Optimize, but no snapshot [" + backupId + "] or [camunda_zeebe_records_backup_" + backupId
                    + "] (the Zeebe records, which Optimize reads). Searched in the repositories " + namesPerRepository.keySet() + ".");
        }
        requireComplete("Operate and Tasklist", webappsParts);
        if (!optimizeParts.isEmpty()) {
            requireComplete("Optimize", optimizeParts);
        }

        List<Planned> plan = new ArrayList<>();
        plan.addAll(sorted(webappsParts));
        plan.addAll(sorted(optimizeParts));
        plan.addAll(records);
        return plan;
    }

    /**
     * The indices of Camunda: the restore deletes them before the snapshots are restored. The names of the documentation:
     * camunda, operate, tasklist, optimize, zeebe. The system indices (.something) are never touched.
     */
    public static boolean isCamundaIndex(String index) {
        if (index == null || index.isBlank() || index.startsWith(".")) {
            return false;
        }
        String name = index.toLowerCase(Locale.ROOT);
        return name.contains("camunda") || name.contains("operate") || name.contains("tasklist")
                || name.contains("optimize") || name.contains("zeebe");
    }

    private static Pattern partPattern(String prefix, long backupId) {
        return Pattern.compile("^" + prefix + backupId + "_.+_part_(\\d+)_of_(\\d+)$");
    }

    private static List<Planned> sorted(List<Planned> parts) {
        return parts.stream().sorted(Comparator.comparingInt(Planned::part)).toList();
    }

    private static void requireComplete(String component, List<Planned> parts) throws OperationException {
        int expected = parts.get(0).partCount();
        List<Integer> found = parts.stream().map(Planned::part).sorted().toList();
        List<Integer> missing = new ArrayList<>();
        for (int part = 1; part <= expected; part++) {
            if (!found.contains(part)) {
                missing.add(part);
            }
        }
        if (!missing.isEmpty() || parts.size() != expected) {
            throw missing(component + ": " + expected + " snapshot parts are expected, " + parts.size() + " found. Missing parts: "
                    + missing + ". The backup is incomplete.");
        }
    }

    private static OperationException missing(String message) {
        return new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 400, "Snapshots not found", message);
    }

    public enum Kind {WEBAPPS, OPTIMIZE, ZEEBE_RECORDS}

    /**
     * A snapshot to restore
     */
    public record Planned(Kind kind, String repository, String name, int part, int partCount) {
    }
}
