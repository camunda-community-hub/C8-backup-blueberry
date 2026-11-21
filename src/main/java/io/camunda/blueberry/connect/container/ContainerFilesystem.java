package io.camunda.blueberry.connect.container;

import io.camunda.blueberry.platform.componentconfig.OrchestrationParameter;
import org.springframework.stereotype.Component;

/**
 * A directory (a Docker volume, a mounted share) shared by Elasticsearch and Zeebe. The repository is of the type "fs":
 * <pre>
 * PUT /_snapshot/{repository}
 * {"type": "fs", "settings": {"location": "/usr/share/elasticsearch/backups/elasticsearch"}}
 * </pre>
 * The location is {@code blueberry.orchestrationConfig.filesystem.path}, as Elasticsearch sees it.
 * <p>
 * Elasticsearch accepts the location only if it is inside one of its {@code path.repo} directories
 * (elasticsearch.yml, or the environment variable {@code path.repo=/usr/share/elasticsearch/backups} in Docker), and if the
 * directory exists and is writable by the elasticsearch user.
 * <p>
 * The base path of the component (zeeberecord, optimize) is not used: the repository points to the directory as it is,
 * so use one repository for all the snapshots (as the documentation of Camunda 8.8 and over does).
 */
@Component
public class ContainerFilesystem implements Container {

    public static final String FILESYSTEM = "filesystem";
    private final OrchestrationParameter orchestrationParameter;

    public ContainerFilesystem(OrchestrationParameter orchestrationParameter) {
        this.orchestrationParameter = orchestrationParameter;
    }

    @Override
    public String getType() {
        return FILESYSTEM;
    }

    @Override
    public String getElasticsearchPayload(String basePathInsideContainer) {
        return String.format("""
                {
                  "type": "fs",
                  "settings": {
                    "location": "%s"
                  }
                }
                """, escape(orchestrationParameter.getParameterString(OrchestrationParameter.FILESYSTEM_PATH)));
    }

    @Override
    public String getInformation() {
        String path = orchestrationParameter.getParameterString(OrchestrationParameter.FILESYSTEM_PATH);
        return "Container type[" + FILESYSTEM + "] location[" + path + "]"
                + (path.isEmpty() ? ": blueberry.orchestrationConfig.filesystem.path is not set" : "")
                + ". Elasticsearch needs path.repo to contain this location, and the directory must be writable by Elasticsearch";
    }

    @Override
    public String getConfigurationProblem() {
        return orchestrationParameter.getParameterString(OrchestrationParameter.FILESYSTEM_PATH).isEmpty()
                ? "blueberry.orchestrationConfig.filesystem.path is not set: it is the directory of the repository, as Elasticsearch sees it "
                + "(for example /usr/share/elasticsearch/backups/elasticsearch)"
                : null;
    }

    /**
     * The path is written in a JSON string
     */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
