# LAB ONLY: a Jenkins controller image with the tools this pipeline needs.
# Tool images are pinned by tag; pin by digest for anything longer-lived.
FROM node:22-bookworm-slim AS node
FROM maven:3.9-eclipse-temurin-21 AS maven
FROM docker:28-cli AS dockercli
FROM alpine/helm:3.17.3 AS helm
FROM jenkins/jenkins:lts-jdk21

USER root
COPY --from=node /usr/local /usr/local
COPY --from=maven /usr/share/maven /usr/share/maven
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
COPY --from=helm /usr/bin/helm /usr/local/bin/helm
RUN ln -s /usr/share/maven/bin/mvn /usr/local/bin/mvn \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl git python3 \
    && rm -rf /var/lib/apt/lists/*
# The controller mounts the host Docker socket for this lab; see the README for
# the substantial security trade-off and the Docker group-ID requirement.
USER jenkins
