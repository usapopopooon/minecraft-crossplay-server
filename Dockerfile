FROM maven:3.9.11-eclipse-temurin-25 AS event-bridge-build

WORKDIR /build
COPY event-bridge/pom.xml ./pom.xml
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY event-bridge/src ./src
RUN mvn --batch-mode --no-transfer-progress verify

FROM itzg/minecraft-server:stable

COPY --from=event-bridge-build /build/target/usapo-event-bridge.jar /plugins/usapo-event-bridge.jar
