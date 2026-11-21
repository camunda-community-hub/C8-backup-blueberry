package io.camunda.blueberry.platform.version;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The version of Blueberry itself (the version of the pom.xml): it is given by the REST API /api/version, so no
 * version is written in the user interface.
 * <p>
 * In the packaged jar, the version is in META-INF/maven/{groupId}/{artifactId}/pom.properties. When Blueberry runs
 * from the IDE, it is in the pom.xml of the working directory.
 */
@Component
public class BlueberryVersion {
    public static final String UNKNOWN = "unknown";
    static final String POM_PROPERTIES = "/META-INF/maven/io.camunda.community/blueberry/pom.properties";

    private final Logger logger = LoggerFactory.getLogger(BlueberryVersion.class);
    private volatile String version;

    public String get() {
        String value = version;
        if (value == null) {
            value = read();
            version = value;
        }
        return value;
    }

    private String read() {
        String fromJar = fromPomProperties(BlueberryVersion.class.getResourceAsStream(POM_PROPERTIES));
        if (fromJar != null) {
            return fromJar;
        }
        String fromPom = fromPomXml(Path.of(System.getProperty("user.dir", "."), "pom.xml"));
        if (fromPom != null) {
            return fromPom;
        }
        String fromManifest = BlueberryVersion.class.getPackage() == null ? null
                : BlueberryVersion.class.getPackage().getImplementationVersion();
        return fromManifest == null ? UNKNOWN : fromManifest;
    }

    /**
     * @param stream the pom.properties, null if absent
     * @return the version, null if it is not there
     */
    static String fromPomProperties(InputStream stream) {
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream) {
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty("version");
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param pom the pom.xml
     * @return the version of the project, null if the file is absent or has none
     */
    String fromPomXml(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
            Model model = new MavenXpp3Reader().read(reader);
            String value = model.getVersion();
            if ((value == null || value.isBlank()) && model.getParent() != null) {
                value = model.getParent().getVersion();
            }
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception e) {
            logger.info("Can't read the version in {}: {}", pom, e.getMessage());
            return null;
        }
    }
}
