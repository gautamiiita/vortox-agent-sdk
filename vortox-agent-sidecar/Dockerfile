# Build stage
FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /app

# Copy POMs first so Maven can validate the multi-module reactor
COPY pom.xml .
COPY vortox-agent-core/pom.xml ./vortox-agent-core/pom.xml
COPY vortox-agent-sidecar/pom.xml ./vortox-agent-sidecar/pom.xml

# Install parent POM into local .m2 (non-recursive — no module sources needed)
RUN mvn -N install -DskipTests

# Install SDK core (source follows POM for better layer caching)
COPY vortox-agent-core/src ./vortox-agent-core/src
RUN mvn -pl vortox-agent-core install -DskipTests

# Build the sidecar
COPY vortox-agent-sidecar/src ./vortox-agent-sidecar/src
RUN mvn -pl vortox-agent-sidecar package -DskipTests

# Runtime stage — needs bash, python3, node for skill execution
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    python3 \
    python3-pip \
    python3-venv \
    python3-requests \
    nodejs \
    npm \
    curl \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Copy the sidecar JAR
COPY --from=build /app/vortox-agent-sidecar/target/*.jar sidecar.jar

# Skills are mounted here by the host application
RUN mkdir -p /app/skills /tmp/vortox-skill-execution

EXPOSE 7860

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:7860/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "sidecar.jar"]
