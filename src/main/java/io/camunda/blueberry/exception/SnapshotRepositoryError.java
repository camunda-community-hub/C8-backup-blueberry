package io.camunda.blueberry.exception;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The snapshot repository does not exist in Elasticsearch. Two components say it:
 * <ul>
 *     <li>Elasticsearch: 404 {"error":{"root_cause":[{"type":"repository_missing_exception","reason":"[camunda] missing"}]...</li>
 *     <li>Optimize: 500 {"errorCode":"serverError", ..., "detailedMessage":"No repository with name [camunda] could be found."...</li>
 * </ul>
 * The user must create it: the Configure of the Configuration tab does it.
 */
public final class SnapshotRepositoryError {
    private static final Pattern OPTIMIZE = Pattern.compile("No repository with name \\[([^\\]]+)\\] could be found");
    private static final Pattern ELASTICSEARCH = Pattern.compile("repository_missing_exception.*?\\[([^\\]]+)\\] missing", Pattern.DOTALL);

    private SnapshotRepositoryError() {
    }

    /**
     * @param text the text of an error (the message of the exception, with the body of the answer)
     * @return the name of the repository which does not exist, null if the error is something else
     */
    public static String detect(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = OPTIMIZE.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = ELASTICSEARCH.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String message(String repositoryName) {
        return "The snapshot repository [" + repositoryName + "] does not exist in Elasticsearch: Please configure your platform (see Configuration tab)";
    }
}
