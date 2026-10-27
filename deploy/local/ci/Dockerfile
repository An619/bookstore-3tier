# LAB ONLY: Jenkins controller with the tools this pipeline uses.
# Pin these images by digest for anything longer-lived than a local lab.
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

# Pre-install plugins so configuration-as-code works on first boot. If plugin
# resolution fails the image still builds and Jenkins falls back to manual setup.
RUN jenkins-plugin-cli --plugins \
      configuration-as-code git workflow-aggregator credentials-binding \
      junit sonar matrix-auth \
    || echo "WARNING: plugin installation failed; configure Jenkins manually."

COPY casc/jenkins.yaml /usr/share/jenkins/ref/casc/jenkins.yaml
COPY init.groovy.d/10-pipeline-job.groovy /usr/share/jenkins/ref/init.groovy.d/10-pipeline-job.groovy

ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc/jenkins.yaml
# The controller mounts the host Docker socket for this lab; see the README for
# the security trade-off and the Docker group-ID requirement.
USER jenkins
