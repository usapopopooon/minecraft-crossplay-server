FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS event-bridge-build

WORKDIR /build
COPY event-bridge/pom.xml ./pom.xml
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY event-bridge/src ./src
RUN mvn --batch-mode --no-transfer-progress verify

FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS inventories-build

WORKDIR /build
COPY multiverse-inventories-isolated ./
RUN mvn --batch-mode --no-transfer-progress verify

FROM itzg/minecraft-server:2026.8.0@sha256:e3335993929a1565f73c30b2041bcbc1473fc9c406fdd5a0d0ea24c08ef73320

COPY paper-patches /usapo-paper-patches
COPY --from=event-bridge-build /build/target/usapo-event-bridge.jar /plugins/usapo-event-bridge.jar
COPY --from=inventories-build /build/target/multiverse-inventories-5.3.6-isolated.jar /plugins/usapo-multiverse-inventories.jar
COPY portal-packs/dist/cyan-portal-bedrock-v1.mcpack /plugins/Geyser-Spigot/packs/cyan-portal-bedrock-v1.mcpack
COPY portal-packs/dist/cyan-portal-v1.json /plugins/Geyser-Spigot/custom_mappings/cyan-portal-v1.json
