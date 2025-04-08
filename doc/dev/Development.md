# Development

## Compilation
The project contains two part
* an SpringBoot application
* a react application

Run
```shell
mvn install
```
on the root folder generate the React application, and the Java application.

Start the springboot application `io.camunda.BlueberryApplication` and access the application via `localhost:9082`

Note: there are multiple profile. Use any "dev" profile to connect.


## Create the image


## Create the Docker image
Because the library contains Java and React script, to deploy it, the machine must have two environments

.github/workflows/mvn-build.yml


`````yaml
    - name: Set up JDK
      uses: actions/setup-java@v3
      with:
          java-version: '17'
          distribution: 'adopt'
    - name: Set up NPM
      uses: actions/setup-node@v3

    - name: Build with Maven
      run:  CI=false mvn --batch-mode --update-snapshots package
`````

CI=false; otherwise, any warning will stop the construction.

The docker image is then available in the package
`https://github.com/camunda-community-hub/C8-backup-toolkit/pkgs/container/blueberry`





# Build
The project is configured to publish the JAR file automatically to Maven Central and docker package a Docker image.

If you want to build a local maven image, use

````shell
mvn spring-boot:build-image
````
## Maven Central repository

See .github/workflows/mvn-release.yml


Visit
https://github.com/camunda-community-hub/community-action-maven-release/tree/main


## Deploy manually the image

Rebuilt the image via
````
mvn clean install
mvn spring-boot:build-image

````
or use 


````
docker build -t pierre-yves-monnet/blueberry:1.0.4 .
````

The docker image is built using the Dockerfile present on the root level.



Push the image to the Camunda hub (you must be login first to the docker registry)

````
docker tag pierre-yves-monnet/blueberry:1.0.4 ghcr.io/camunda-community-hub/blueberry:latest

docker push ghcr.io/camunda-community-hub/blueberry:latest
````


Tag as the latest:
````
docker tag pierre-yves-monnet/blueberry:1.0.0 ghcr.io/camunda-community-hub/blueberry:latest
docker push ghcr.io/camunda-community-hub/blueberry:latest
````

Check on
https://github.com/camunda-community-hub/blueberry/pkgs/container


