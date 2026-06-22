# Build stage
FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /app

# Install SDK core first
COPY vortox-agent-core/ ./vortox-agent-core/
COPY pom.xml .
RUN mvn -pl vortox-agent-core install -DskipTests

# Build the sidecar
COPY vortox-agent-sidecar/ ./vortox-agent-sidecar/
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
