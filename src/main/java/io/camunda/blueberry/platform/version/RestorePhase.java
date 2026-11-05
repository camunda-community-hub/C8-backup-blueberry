package io.camunda.blueberry.platform.version;

/**
 * One step in the version-specific restore procedure.
 */
public record RestorePhase(
        String id,
        String title,
        PhaseKind kind,
        String manualHint
) {
    public enum PhaseKind {AUTOMATED, MANUAL}

    public static RestorePhase automated(String id, String title) {
        return new RestorePhase(id, title, PhaseKind.AUTOMATED, null);
    }

    public static RestorePhase manual(String id, String title, String manualHint) {
        return new RestorePhase(id, title, PhaseKind.MANUAL, manualHint);
    }
}
