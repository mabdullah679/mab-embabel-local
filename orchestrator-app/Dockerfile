FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY shared-models/pom.xml shared-models/pom.xml
COPY orchestrator-app/pom.xml orchestrator-app/pom.xml
COPY tools-app/pom.xml tools-app/pom.xml

RUN mvn -q -pl orchestrator-app -am dependency:go-offline

COPY shared-models shared-models
COPY orchestrator-app orchestrator-app

RUN mvn -q -pl orchestrator-app -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/orchestrator-app/target/orchestrator-app-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
