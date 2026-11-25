package io.camunda.blueberry.connect.cluster;

import io.camunda.blueberry.config.BlueberryConfig;
import io.camunda.blueberry.connect.BackupComponentInt;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Docker implementation: Optimize runs in a container, stopped and started with the docker command line (it works with the
 * Docker Desktop of Windows, without opening the Docker API).
 * The container is searched in Docker: every container whose name contains blueberry.cluster.docker.optimizeContainerFilter
 * (docker ps --all --filter name=...). The Docker Compose names (c-8-10-optimize-1) do not have to be known.
 * <pre>
 * blueberry:
 *   cluster:
 *     type: docker
 *     docker:
 *       optimizeContainerFilter: optimize
 * </pre>
 */
@Component
public class DockerConnect implements ClusterConnect {
    public static final String NAME = "docker";
    private static final BackupComponentInt.COMPONENT OPTIMIZE = BackupComponentInt.COMPONENT.OPTIMIZE;
    private static final int COMMAND_TIMEOUT_SECONDS = 30;
    private static final String SEPARATOR = "|";

    private final BlueberryConfig blueberryConfig;
    private final Logger logger = LoggerFactory.getLogger(DockerConnect.class);

    public DockerConnect(BlueberryConfig blueberryConfig) {
        this.blueberryConfig = blueberryConfig;
    }

    static List<DockerContainer> parseContainers(String output) {
        List<DockerContainer> containers = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\" + SEPARATOR, 2);
            boolean running = parts.length > 1 && "running".equalsIgnoreCase(parts[1].trim());
            containers.add(new DockerContainer(parts[0].trim(), running));
        }
        return containers;
    }

    private static String display(List<String> command) {
        return String.join(" ", command);
    }

    private static String fixConfiguration() {
        return "# Blueberry configuration (application.yaml)\n"
                + "blueberry:\n"
                + "  cluster:\n"
                + "    type: docker\n"
                + "    docker:\n"
                + "      command: docker\n"
                + "      optimizeContainerFilter: optimize";
    }

    /* ******************************************************************** */
    /*  Check                                                               */
    /* ******************************************************************** */

    @Override
    public String getName() {
        return NAME;
    }

    /* ******************************************************************** */
    /*  Stop / start                                                        */
    /* ******************************************************************** */

    @Override
    public List<Step> checkAccess() {
        List<Step> steps = new ArrayList<>();
        // 1. The docker command answers
        List<String> versionCommand = docker("version", "--format", "{{.Server.Version}}");
        CommandResult version = run(versionCommand, COMMAND_TIMEOUT_SECONDS);
        if (!version.success()) {
            steps.add(Step.failed("Connect to Docker", display(versionCommand),
                    "Blueberry can't run the docker command: " + version.output() + "\n"
                            + "Install Docker (or Docker Desktop) where Blueberry runs, check that 'docker version' works in a terminal, "
                            + "and set blueberry.cluster.docker.command if the docker executable is not in the PATH.",
                    fixConfiguration()));
            return steps;
        }
        steps.add(Step.correct("Connect to Docker: server version " + version.output().trim(), display(versionCommand)));

        // 2. The containers of Optimize: the name contains the filter
        String filter = filter();
        List<String> psCommand = psCommand(filter);
        Found found = findContainers();
        if (found.failed()) {
            steps.add(Step.failed("Search the containers with [" + filter + "] in the name", display(psCommand), found.error(), fixConfiguration()));
        } else if (found.containers().isEmpty()) {
            steps.add(Step.failed("Search the containers with [" + filter + "] in the name", display(psCommand),
                    "No container has [" + filter + "] in its name. Set blueberry.cluster.docker.optimizeContainerFilter "
                            + "(docker ps --all, column NAMES).",
                    fixConfiguration()));
        } else {
            StringBuilder list = new StringBuilder();
            for (DockerContainer container : found.containers()) {
                String name = container.name();
                boolean running = container.running();
                list.append(list.length() > 0 ? ", " : "").append(name).append(running ? " (running)" : " (stopped)");
            }
            steps.add(Step.correct("Containers with [" + filter + "] in the name: " + list, display(psCommand)));
        }
        return steps;
    }

    @Override
    public int stopOptimize(OperationLog log) throws OperationException {
        Found found = mustFind();
        int stopped = 0;
        int timeout = blueberryConfig.getDockerStopTimeoutSeconds();
        for (DockerContainer container : found.containers()) {
            String name = container.name();
            boolean running = container.running();
            if (!running) {
                log.info(OPTIMIZE, "The container " + name + " is already stopped");
                continue;
            }
            log.info(OPTIMIZE, "Stop the container " + name + " (docker stop " + name + ")");
            CommandResult stop = run(docker("stop", "--time", String.valueOf(timeout), name), timeout + COMMAND_TIMEOUT_SECONDS);
            if (!stop.success()) {
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 500, "Can't stop Optimize",
                        "docker stop " + name + " failed: " + stop.output());
            }
            stopped++;
        }
        log.info(OPTIMIZE, stopped == 0 ? "Optimize is already stopped" : "Optimize is stopped");
        return stopped;
    }

    /* ******************************************************************** */
    /*  Search                                                              */
    /* ******************************************************************** */

    @Override
    public void startOptimize(int replicas, OperationLog log) throws OperationException {
        if (replicas <= 0) {
            return;
        }
        // every container of the filter which is not running
        for (DockerContainer container : mustFind().containers()) {
            String name = container.name();
            boolean running = container.running();
            if (running) {
                continue;
            }
            log.info(OPTIMIZE, "Start the container " + name + " (docker start " + name + ")");
            CommandResult start = run(docker("start", name), COMMAND_TIMEOUT_SECONDS);
            if (!start.success()) {
                throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 500, "Can't start Optimize",
                        "docker start " + name + " failed: " + start.output());
            }
        }
        log.info(OPTIMIZE, "Optimize is started");
    }

    private String filter() {
        return blueberryConfig.getDockerOptimizeContainerFilter();
    }

    private List<String> psCommand(String filter) {
        return docker("ps", "--all", "--filter", "name=" + filter, "--format", "{{.Names}}" + SEPARATOR + "{{.State}}");
    }

    /**
     * Every container (running or not) whose name contains the filter
     */
    Found findContainers() {
        CommandResult result = run(psCommand(filter()), COMMAND_TIMEOUT_SECONDS);
        if (!result.success()) {
            return new Found(List.of(), "docker ps failed: " + result.output());
        }
        return new Found(parseContainers(result.output()), null);
    }

    private Found mustFind() throws OperationException {
        Found found = findContainers();
        if (found.failed()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 500, "Docker error", found.error());
        }
        if (found.containers().isEmpty()) {
            throw new OperationException(OperationException.BLUEBERRYERRORCODE.CHECK, 404, "Optimize container not found",
                    "No container has [" + filter() + "] in its name. Set blueberry.cluster.docker.optimizeContainerFilter.");
        }
        return found;
    }

    /* ******************************************************************** */
    /*  Tools                                                               */
    /* ******************************************************************** */

    private List<String> docker(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(blueberryConfig.getDockerCommand());
        String host = blueberryConfig.getDockerHost();
        if (host != null) {
            command.add("--host");
            command.add(host);
        }
        command.addAll(List.of(arguments));
        return command;
    }

    private CommandResult run(List<String> command, int timeoutSeconds) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(-1, "Timeout after " + timeoutSeconds + " seconds: " + output);
            }
            return new CommandResult(process.exitValue(), output.trim());
        } catch (IOException e) {
            logger.debug("Can't run {}: {}", command, e.getMessage());
            return new CommandResult(-1, "Can't run [" + command.get(0) + "]: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "Interrupted");
        }
    }

    /**
     * A container found by the filter
     */
    record DockerContainer(String name, boolean running) {
    }

    /**
     * The containers found, or the error of the docker command
     */
    record Found(List<DockerContainer> containers, String error) {
        boolean failed() {
            return error != null;
        }
    }

    record CommandResult(int exitCode, String output) {
        boolean success() {
            return exitCode == 0;
        }
    }
}

