# Build stage. Dependencies are resolved in their own layer so a code change
# does not re-download the whole repository.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# Tests run in CI and locally, not here: this stage packages, it does not verify.
RUN mvn -B -DskipTests package

# Runtime stage. JRE only, no Maven, no sources.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Never root: the quarantine holds files that were not scanned yet.
RUN useradd --system --create-home --uid 1001 sfs \
    && mkdir -p /var/lib/sfs \
    && chown -R sfs:sfs /var/lib/sfs
USER sfs

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
