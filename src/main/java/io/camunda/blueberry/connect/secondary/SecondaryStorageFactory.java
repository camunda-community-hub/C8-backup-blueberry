package io.camunda.blueberry.connect.secondary;

import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Give the object in charge of the backup of the secondary storage (Elasticsearch, SQL Server...).
 * The secondary storage object is a backup component: it is added to the list of the components to backup.
 */
@Component
public class SecondaryStorageFactory {

    private final List<SecondaryStorageInt> secondaryStorageList;
    private final OrchestrationParameter orchestrationParameter;

    public SecondaryStorageFactory(List<SecondaryStorageInt> secondaryStorageList, OrchestrationParameter orchestrationParameter) {
        this.secondaryStorageList = secondaryStorageList;
        this.orchestrationParameter = orchestrationParameter;
    }

    /**
     * The secondary storage of the cluster: the parameter secondaryStorage (the actuator of Zeebe, else the configuration)
     *
     * @return the parameter: its value (null if unknown) and its origin
     */
    public OrchestrationParameter.Parameter getValue() {
        return orchestrationParameter.getParameter(OrchestrationParameter.SECONDARY_STORAGE);
    }

    /**
     * @return the value of the secondary storage as a text, empty if it is unknown
     */
    public String getValueString() {
        return orchestrationParameter.getParameterString(OrchestrationParameter.SECONDARY_STORAGE);
    }

    /**
     * @return the object in charge of the secondary storage of the cluster, null if it is unknown or if there is no implementation for it.
     * For "rdbms", the vendor is read in the JDBC url of the cluster (see OrchestrationParameter.getSecondaryStorageKind)
     */
    public SecondaryStorageInt getSecondaryStorage() {
        return getSecondaryStorage(orchestrationParameter.getSecondaryStorageKind());
    }

    /**
     * @param secondaryStorage name of the storage, the case, the spaces, - and _ are ignored: "elasticsearch", "SQL Server"
     * @return the object, null if there is no implementation for this name
     */
    public SecondaryStorageInt getSecondaryStorage(String secondaryStorage) {
        String wanted = normalize(secondaryStorage);
        if (wanted.isEmpty()) {
            return null;
        }
        Optional<SecondaryStorageInt> found = secondaryStorageList.stream()
                .filter(t -> normalize(t.getName()).equals(wanted)
                        || t.getAliases().stream().anyMatch(alias -> normalize(alias).equals(wanted)))
                .findFirst();
        return found.orElse(null);
    }

    /**
     * @return the names of the secondary storage which can be backed up
     */
    public List<String> getSupportedNames() {
        return secondaryStorageList.stream().map(SecondaryStorageInt::getName).toList();
    }

    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
