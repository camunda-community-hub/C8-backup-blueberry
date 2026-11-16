package io.camunda.blueberry.connect.secondary;

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
    private final SecondaryStorageDetector secondaryStorageDetector;

    SecondaryStorageFactory(List<SecondaryStorageInt> secondaryStorageList, SecondaryStorageDetector secondaryStorageDetector) {
        this.secondaryStorageList = secondaryStorageList;
        this.secondaryStorageDetector = secondaryStorageDetector;
    }

    /**
     * The secondary storage of the cluster: detected on the cluster, else given by the configuration
     *
     * @return the object, null if the secondary storage is unknown or if there is no implementation for it
     */
    public SecondaryStorageInt getSecondaryStorage() {
        return getSecondaryStorage(secondaryStorageDetector.detect().value());
    }

    /**
     * What is known on the secondary storage: the detected value, the configured value, the one used
     */
    public SecondaryStorageDetector.Detection detect() {
        return secondaryStorageDetector.detect();
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
                .filter(t -> normalize(t.getName()).equals(wanted))
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
