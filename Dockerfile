# docker build -t zeebe-cherry:1.0.0 .
FROM eclipse-temurin:25-jdk-alpine
EXPOSE 9081
COPY target/blueberry-*-exec.jar /blueberry.jar
COPY pom.xml /pom.xml

WORKDIR  /

ENTRYPOINT ["java","-jar","/blueberry.jar"]

