package io.camunda.blueberry.connect.container;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ContainerFactory {

    @Autowired
    List<Container> containers;

    public Container getContainerFromType(String containerType) {
        return containers.stream()
                .filter(container -> containerType.equals(container.getType()))
                .findFirst()
                .orElse(null);

    }
}
