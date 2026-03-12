FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY shared-models/pom.xml shared-models/pom.xml
COPY tools-app/pom.xml tools-app/pom.xml
COPY orchestrator-app/pom.xml orchestrator-app/pom.xml

RUN mvn -q -pl tools-app -am dependency:go-offline

COPY shared-models shared-models
COPY tools-app tools-app

RUN mvn -q -pl tools-app -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/tools-app/target/tools-app-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
