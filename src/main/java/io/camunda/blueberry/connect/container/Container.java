package io.camunda.blueberry.connect.container;

public interface Container {


    /**
     * return the type of the container
     *
     * @return the type
     */
    String getType();

    /**
     * getElasticsearcPayload
     *
     * @return the payload, to send to Elasticsearch to create the repository linked to the container
     */
    String getElasticsearchPayload(String basePathInsideContainer);


    /**
     * Return information about the container, to display to the user to help him to debug the situation
     *
     * @return
     */
    String getInformation();
}
