package io.camunda.blueberry.connect;

/**
 * The answer of the connection to a component: connected or not, and the explanation (the url used...)
 */
public class ConnectionInfo {
    public boolean isConnected;
    public String explanations;

    public ConnectionInfo(boolean connected, String explanations) {
        this.isConnected = connected;
        this.explanations = explanations;
    }
}
