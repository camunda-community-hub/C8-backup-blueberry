package io.camunda.blueberry.connect.cluster;

/**
 * A Deployment or StatefulSet that restore scales.
 */
public record WorkloadRef(Kind kind, String name) {

    public static WorkloadRef deployment(String name) {
        return new WorkloadRef(Kind.DEPLOYMENT, name);
    }

    public static WorkloadRef statefulSet(String name) {
        return new WorkloadRef(Kind.STATEFUL_SET, name);
    }

    public enum Kind {DEPLOYMENT, STATEFUL_SET}
}
