# CI/CD setup guide

## GitHub Actions

The workflow in `.github/workflows/ci.yml` runs Maven catalog tests, backend tests, frontend lint/build, Helm lint/render, Terraform validation and a Docker Compose smoke test for pull requests targeting `main` or the staged platform branch and pushes to those branches.

Review the Actions tab after each PR update. A green run validates these checks only; it does not prove an EKS deployment or production readiness.

## Jenkins prerequisites

Install/configure on the Jenkins controller and agent:
- Git and a GitHub SCM connection.
- Node.js 22 and npm.
- Java 21 and Maven for the Spring Boot catalog service.
- Docker Engine, Docker Compose v2 and permission for the agent to use Docker.
- curl and Helm 3.
- Optional: SonarScanner CLI, Trivy and a Docker Hub account for publishing.

Use an isolated agent. Membership in the Docker group is effectively privileged access to the host; do not grant it to untrusted users or builds.

## Create a Multibranch Pipeline

1. Create a Jenkins Multibranch Pipeline job.
2. Add the GitHub repository URL and configure credentials if the repository requires them.
3. Discover branches and pull requests.
4. Set the Jenkinsfile path to `Jenkinsfile`.
5. Run a build on the feature branch before enabling any publishing.

## Optional SonarQube

The Jenkinsfile defaults `SONARQUBE_ENABLED=false`. Configure:
- A Jenkins secret-text credential named `sonarqube-token`.
- Environment variable `SONAR_HOST_URL` on the agent/job.
- SonarScanner CLI on the agent.
- The project key and source settings in `sonar-project.properties` (Java binaries are produced by the Maven stage).

Only then set `SONARQUBE_ENABLED=true`. Configure a quality gate in SonarQube separately if you need the pipeline to wait for it.

## Optional Trivy

Install Trivy on the Jenkins agent and set `TRIVY_ENABLED=true`. High/Critical unfixed findings fail the scan stage. Review findings and update vulnerable dependencies/base images rather than adding blanket ignores.

## Optional Docker Hub publishing

Create a Jenkins username/password credential with ID `dockerhub-creds`, where the password is a Docker Hub access token. Set `PUBLISH_IMAGES=true` only after testing. Publishing is limited to the `main` branch and tags images with the Git commit SHA prefix.

Do not put tokens in the Jenkinsfile, repository, shell command arguments or logs. Restrict who can modify the pipeline and who can trigger publishing.

## Optional AWS ECR publishing

The Jenkinsfile also exposes the PUBLISH_TO_ECR build parameter. Use it only on the main branch after Terraform has created all three ECR repositories.

1. Attach a narrowly scoped AWS IAM role/identity to the Jenkins agent; avoid long-lived access keys when possible.
2. Ensure the agent has AWS CLI v2 and Docker installed.
3. Set AWS_REGION to the Terraform region, ECR_REGISTRY to the account registry hostname, and repository names to the Terraform-created names (default: bookstore-lab/backend, bookstore-lab/frontend and bookstore-lab/catalog).
4. Confirm the agent identity can authenticate to ECR and push to those three repositories.
5. Run the main-branch build with PUBLISH_TO_ECR enabled. The images are tagged with the source commit SHA prefix.

The ECR registry/repositories must already exist. This stage publishes artifacts only; it does not deploy to EKS or change Argo CD configuration. Use a reviewed GitOps change to promote the tested image tags.
