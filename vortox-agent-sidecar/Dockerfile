# Build stage
FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /app

# Copy POMs first so Maven can validate the multi-module reactor — every module listed in the
# parent pom.xml's <modules> must have its pom.xml present here, even ones (like
# vortox-agent-spring-boot-starter and vortox-agent-classic-mvc-proxy) this image never builds,
# or reactor resolution fails for every mvn invocation below, not just a full build.
COPY pom.xml .
COPY vortox-agent-core/pom.xml ./vortox-agent-core/pom.xml
COPY vortox-agent-sidecar/pom.xml ./vortox-agent-sidecar/pom.xml
COPY vortox-agent-spring-boot-starter/pom.xml ./vortox-agent-spring-boot-starter/pom.xml
COPY vortox-agent-classic-mvc-proxy/pom.xml ./vortox-agent-classic-mvc-proxy/pom.xml

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
    wget \
    unzip \
    jq \
    git \
    libaio1 \
    && pip3 install --no-cache-dir pymysql oracledb \
    && rm -rf /var/lib/apt/lists/*

# Oracle Instant Client — required for NNE (Native Network Encryption) connections
RUN wget -q https://download.oracle.com/otn_software/linux/instantclient/instantclient-basic-linuxx64.zip \
         -O /tmp/oic.zip && \
    mkdir -p /opt/oracle && \
    unzip -q /tmp/oic.zip -d /opt/oracle && \
    mv /opt/oracle/instantclient_* /opt/oracle/instantclient && \
    rm /tmp/oic.zip

ENV LD_LIBRARY_PATH=/opt/oracle/instantclient
ENV ORACLE_INSTANT_CLIENT_DIR=/opt/oracle/instantclient

# Copy the sidecar JAR
COPY --from=build /app/vortox-agent-sidecar/target/*.jar sidecar.jar

# Skills are mounted here by the host application
RUN mkdir -p /app/skills /tmp/vortox-skill-execution /tmp/oracle_admin /tmp/oracle_exports

EXPOSE 7860

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD curl -f http://localhost:7860/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "sidecar.jar"]
