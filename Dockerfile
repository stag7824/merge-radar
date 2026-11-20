# Build the CLI jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn --batch-mode --no-transfer-progress --quiet package -DskipTests -Dspotless.check.skip=true

# Run it on a plain JRE.
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/stag7824/merge-radar" \
      org.opencontainers.image.description="Find open source issues you can actually fix and get merged." \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=build /src/cli/target/merge-radar.jar /opt/merge-radar/merge-radar.jar
COPY action/entrypoint.sh /opt/merge-radar/entrypoint.sh
RUN chmod +x /opt/merge-radar/entrypoint.sh
ENTRYPOINT ["java", "-jar", "/opt/merge-radar/merge-radar.jar"]
