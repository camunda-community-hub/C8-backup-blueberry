package io.camunda.blueberry.platform.componentconfig;

/**
 * A parameter of the orchestration cluster
 */
public class Parameter {
    private final String name;
    private final Object value;
    private final Origin origin;
    public Parameter(String name, Object value, Origin origin) {
        this.name = name;
        this.value = value;
        this.origin = origin;
    }

    public String getName() {
        return name;
    }

    /**
     * @return the value, null if neither the actuator nor the configuration gave it
     */
    public Object getValue() {
        return value;
    }

    /**
     * @return the value as a text, empty if neither the actuator nor the configuration gave it
     */
    public String getValueString() {
        return value == null ? "" : value.toString();
    }

    /**
     * @return the name of the parameter, and where its value comes from: "backupRepositoryName (config)". To explain a value to the user
     */
    public String describe() {
        return name + " (" + getOriginLabel() + ")";
    }

    public Origin getOrigin() {
        return origin;
    }

    /**
     * @return the origin in lower case ("actuator", "config"), to display it
     */
    public String getOriginLabel() {
        return origin.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Where the value comes from
     */
    public enum Origin {
        /**
         * /actuator/env of Zeebe
         */
        ACTUATOR,
        /**
         * The configuration of Blueberry (application.yaml): the actuator can't be reached, or it does not give the variable
         */
        CONFIG
    }
}
