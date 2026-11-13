FROM eclipse-temurin:21-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
ARG UBUNTU_REPOSITORY=ports.ubuntu.com/ubuntu-ports
RUN sed -i "s#ports.ubuntu.com/ubuntu-ports#${UBUNTU_REPOSITORY}#g" /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade --yes \
    && rm -f /usr/bin/pebble \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN useradd --system --uid 10001 --create-home agent
COPY target/minimal-agent-runtime-1.0-SNAPSHOT.jar /app/app.jar
COPY eval/scenarios-v1.json /app/eval/scenarios-v1.json
COPY eval/scenarios-v2.json /app/eval/scenarios-v2.json
USER 10001:10001
ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
