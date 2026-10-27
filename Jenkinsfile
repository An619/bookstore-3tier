pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 45, unit: 'MINUTES')
    }

    parameters {
        booleanParam(name: 'SONARQUBE_ENABLED', defaultValue: false, description: 'Run SonarQube analysis; requires SonarScanner and Jenkins credential sonarqube-token.')
        booleanParam(name: 'TRIVY_ENABLED', defaultValue: false, description: 'Scan built images; requires Trivy installed on the agent.')
        booleanParam(name: 'PUBLISH_IMAGES', defaultValue: false, description: 'Publish commit-tagged images to Docker Hub from main; requires dockerhub-creds.')
        booleanParam(name: 'PUBLISH_TO_ECR', defaultValue: false, description: 'Publish commit-tagged images to AWS ECR from main; requires AWS CLI, AWS credentials and existing ECR repositories.')
        string(name: 'AWS_REGION', defaultValue: 'ap-south-1', description: 'AWS region containing the ECR repositories.')
        string(name: 'ECR_REGISTRY', defaultValue: '', description: 'ECR registry host, for example account-id.dkr.ecr.ap-south-1.amazonaws.com.')
        string(name: 'ECR_BACKEND_REPOSITORY', defaultValue: 'bookstore-lab/backend', description: 'Backend ECR repository name created by Terraform.')
        string(name: 'ECR_FRONTEND_REPOSITORY', defaultValue: 'bookstore-lab/frontend', description: 'Frontend ECR repository name created by Terraform.')
        string(name: 'ECR_CATALOG_REPOSITORY', defaultValue: 'bookstore-lab/catalog-service', description: 'Java catalog microservice ECR repository name created by Terraform.')
        string(name: 'ECR_ORDERS_REPOSITORY', defaultValue: 'bookstore-lab/orders-service', description: 'Orders microservice ECR repository name.')
        string(name: 'ECR_INVENTORY_REPOSITORY', defaultValue: 'bookstore-lab/inventory-service', description: 'Inventory microservice ECR repository name.')
    }

    environment {
        BACKEND_IMAGE = 'bookstore-backend'
        FRONTEND_IMAGE = 'bookstore-frontend'
        CATALOG_IMAGE = 'bookstore-catalog-service'
        ORDERS_IMAGE = 'bookstore-orders-service'
        INVENTORY_IMAGE = 'bookstore-inventory-service'
        IMAGE_TAG = ''
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                }
            }
        }

        stage('Backend validation') {
            steps {
                dir('backend') {
                    sh 'node --version'
                    sh 'npm ci'
                    sh 'npm test'
                    sh 'npm audit --audit-level=high'
                }
            }
        }

        stage('Maven microservices verification') {
            steps {
                sh 'java -version'
                sh 'mvn -B -ntp verify'
                sh 'python3 -m unittest discover -s tests -v'
            }
            post {
                always {
                    junit 'services/*/target/surefire-reports/*.xml'
                    archiveArtifacts artifacts: 'services/*/target/site/jacoco/**', allowEmptyArchive: true
                }
            }
        }

        stage('Frontend validation') {
            steps {
                dir('frontend') {
                    sh 'node --version'
                    sh 'npm ci'
                    sh 'npm run lint'
                    sh 'npm run build'
                    sh 'npm audit --audit-level=high'
                }
            }
        }

        stage('SonarQube analysis') {
            when {
                expression { params.SONARQUBE_ENABLED }
            }
            steps {
                withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                    sh '''
                        set -eu
                        test -n "$SONAR_HOST_URL"
                        command -v sonar-scanner
                        sonar-scanner                           -Dsonar.host.url="$SONAR_HOST_URL"                           -Dsonar.token="$SONAR_TOKEN"
                    '''
                }
            }
        }

        stage('Build container images') {
            steps {
                sh 'docker build -t $BACKEND_IMAGE:$IMAGE_TAG ./backend'
                sh 'docker build -t $FRONTEND_IMAGE:$IMAGE_TAG ./frontend'
                sh 'docker build -t $CATALOG_IMAGE:$IMAGE_TAG -f ./services/catalog-service/Dockerfile .'
                sh 'docker build -t $ORDERS_IMAGE:$IMAGE_TAG -f ./services/orders-service/Dockerfile .'
                sh 'docker build -t $INVENTORY_IMAGE:$IMAGE_TAG -f ./services/inventory-service/Dockerfile .'
            }
        }

        stage('Trivy image scan') {
            when {
                expression { params.TRIVY_ENABLED }
            }
            steps {
                sh '''
                    set -eu
                    command -v trivy
                    trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$BACKEND_IMAGE:$IMAGE_TAG"
                    trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$FRONTEND_IMAGE:$IMAGE_TAG"
                    trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$CATALOG_IMAGE:$IMAGE_TAG"
                    trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$ORDERS_IMAGE:$IMAGE_TAG"
                    trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$INVENTORY_IMAGE:$IMAGE_TAG"
                '''
            }
        }

        stage('Helm chart validation') {
            steps {
                sh '''
                    set -eu
                    command -v helm
                    helm lint ./helm/frontend
                    helm template bookstore-frontend ./helm/frontend --namespace bookstore > /tmp/frontend-rendered.yaml
                    helm lint ./helm/bookstore
                    helm template bookstore ./helm/bookstore --namespace bookstore > /tmp/bookstore-rendered.yaml
                    test -s /tmp/bookstore-rendered.yaml
                    helm lint ./helm/catalog-service
                    helm template catalog-service ./helm/catalog-service --namespace bookstore > /tmp/catalog-rendered.yaml
                    test -s /tmp/catalog-rendered.yaml
                    helm lint ./helm/orders-service
                    helm template orders-service ./helm/orders-service --namespace bookstore > /tmp/orders-rendered.yaml
                    test -s /tmp/orders-rendered.yaml
                    helm lint ./helm/inventory-service
                    helm template inventory-service ./helm/inventory-service --namespace bookstore > /tmp/inventory-rendered.yaml
                    test -s /tmp/inventory-rendered.yaml
                    for chart in catalog-service orders-service inventory-service; do
                      helm template "$chart" "./helm/$chart" --namespace bookstore \
                        --set database.createSecret=true --set database.password=lab-only > "/tmp/$chart-lab.yaml"
                      grep -q 'kind: Secret' "/tmp/$chart-lab.yaml"
                      if helm template "$chart" "./helm/$chart" --set database.createSecret=true >/dev/null 2>&1; then
                        echo "ERROR: $chart must refuse createSecret without a password"; exit 1
                      fi
                    done
                    helm template bookstore-frontend ./helm/frontend --namespace bookstore \
                      --set service.type=NodePort --set service.nodePort=30080 > /tmp/frontend-lab.yaml
                    grep -q 'nodePort: 30080' /tmp/frontend-lab.yaml
                '''
            }
        }

        stage('Compose integration smoke test') {
            steps {
                sh 'FRONTEND_PORT=18080 CATALOG_PORT=18081 ORDERS_PORT=18082 INVENTORY_PORT=18083 COMPOSE_UP_LOG=compose-up.log sh scripts/compose-up.sh'
                sh '''
                    set -eu
                    # The pipeline uses its own host ports so it never collides
                    # with the local kind lab (8080-8083).
                    FRONTEND_PORT=${FRONTEND_PORT:-18080}
                    CATALOG_PORT=${CATALOG_PORT:-18081}
                    ORDERS_PORT=${ORDERS_PORT:-18082}
                    INVENTORY_PORT=${INVENTORY_PORT:-18083}
                    export FRONTEND_PORT CATALOG_PORT ORDERS_PORT INVENTORY_PORT
                    attempt=0
                    until curl --fail --silent "http://localhost:$CATALOG_PORT/actuator/health/readiness" | grep -q '"status":"UP"'; do
                      attempt=$((attempt + 1))
                      if [ "$attempt" -ge 20 ]; then docker compose logs; exit 1; fi
                      sleep 3
                    done
                    curl --fail --silent http://localhost:$FRONTEND_PORT/ > /dev/null
                    curl --fail --silent http://localhost:$FRONTEND_PORT/api/catalog/books | grep -q "Kubernetes Basics"
                    curl --fail --silent http://localhost:$CATALOG_PORT/api/catalog/books | grep -q "Kubernetes Basics"
                    attempt=0
                    until curl --fail --silent http://localhost:$ORDERS_PORT/actuator/health/readiness | grep -q '"status":"UP"'; do
                      attempt=$((attempt + 1)); if [ "$attempt" -ge 20 ]; then docker compose logs; exit 1; fi; sleep 3
                    done
                    attempt=0
                    until curl --fail --silent http://localhost:$INVENTORY_PORT/actuator/health/readiness | grep -q '"status":"UP"'; do
                      attempt=$((attempt + 1)); if [ "$attempt" -ge 20 ]; then docker compose logs; exit 1; fi; sleep 3
                    done
                    curl --fail --silent http://localhost:$ORDERS_PORT/api/orders | grep -q "CREATED"
                    # Checkout pipeline gauges must be scraped from the real container.
                    curl --fail --silent http://localhost:$ORDERS_PORT/actuator/prometheus | grep -q "checkout_outbox_pending"
                    curl --fail --silent http://localhost:$INVENTORY_PORT/api/inventory | grep -q "availableQuantity"
                    curl --fail --silent http://localhost:$FRONTEND_PORT/api/orders | grep -q "CREATED"
                    curl --fail --silent http://localhost:$FRONTEND_PORT/api/inventory | grep -q "availableQuantity"
                '''
                sh 'python3 scripts/smoke-test.py --base-url http://localhost:18080'
                sh 'sh scripts/database-backup.sh backups/ci'
                sh 'sh scripts/database-restore-test.sh backups/ci'
            }
            post {
                always {
                    sh 'docker compose logs --no-color || true'
                    sh 'docker compose down -v --remove-orphans || true'
                }
            }
        }


        stage('Publish images to AWS ECR') {
            when {
                allOf {
                    branch 'main'
                    expression { params.PUBLISH_TO_ECR }
                }
            }
            steps {
                sh '''
                    set -eu
                    test -n "$ECR_REGISTRY"
                    command -v aws
                    aws sts get-caller-identity >/dev/null
                    BACKEND_REMOTE="$ECR_REGISTRY/$ECR_BACKEND_REPOSITORY:$IMAGE_TAG"
                    FRONTEND_REMOTE="$ECR_REGISTRY/$ECR_FRONTEND_REPOSITORY:$IMAGE_TAG"
                    CATALOG_REMOTE="$ECR_REGISTRY/$ECR_CATALOG_REPOSITORY:$IMAGE_TAG"
                    ORDERS_REMOTE="$ECR_REGISTRY/$ECR_ORDERS_REPOSITORY:$IMAGE_TAG"
                    INVENTORY_REMOTE="$ECR_REGISTRY/$ECR_INVENTORY_REPOSITORY:$IMAGE_TAG"
                    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"
                    trap 'docker logout "$ECR_REGISTRY" >/dev/null 2>&1 || true' EXIT
                    docker tag "$BACKEND_IMAGE:$IMAGE_TAG" "$BACKEND_REMOTE"
                    docker tag "$FRONTEND_IMAGE:$IMAGE_TAG" "$FRONTEND_REMOTE"
                    docker tag "$CATALOG_IMAGE:$IMAGE_TAG" "$CATALOG_REMOTE"
                    docker tag "$ORDERS_IMAGE:$IMAGE_TAG" "$ORDERS_REMOTE"
                    docker tag "$INVENTORY_IMAGE:$IMAGE_TAG" "$INVENTORY_REMOTE"
                    docker push "$BACKEND_REMOTE"
                    docker push "$FRONTEND_REMOTE"
                    docker push "$CATALOG_REMOTE"
                    docker push "$ORDERS_REMOTE"
                    docker push "$INVENTORY_REMOTE"
                '''
            }
        }

        stage('Publish images to Docker Hub') {
            when {
                allOf {
                    branch 'main'
                    expression { params.PUBLISH_IMAGES }
                }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKERHUB_USERNAME', passwordVariable: 'DOCKERHUB_TOKEN')]) {
                    sh '''
                        set -eu
                        BACKEND_REMOTE="docker.io/$DOCKERHUB_USERNAME/bookstore-backend:$IMAGE_TAG"
                        FRONTEND_REMOTE="docker.io/$DOCKERHUB_USERNAME/bookstore-frontend:$IMAGE_TAG"
                        CATALOG_REMOTE="docker.io/$DOCKERHUB_USERNAME/bookstore-catalog-service:$IMAGE_TAG"
                        ORDERS_REMOTE="docker.io/$DOCKERHUB_USERNAME/bookstore-orders-service:$IMAGE_TAG"
                        INVENTORY_REMOTE="docker.io/$DOCKERHUB_USERNAME/bookstore-inventory-service:$IMAGE_TAG"
                        echo "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USERNAME" --password-stdin
                        trap 'docker logout >/dev/null 2>&1 || true' EXIT
                        docker tag "$BACKEND_IMAGE:$IMAGE_TAG" "$BACKEND_REMOTE"
                        docker tag "$FRONTEND_IMAGE:$IMAGE_TAG" "$FRONTEND_REMOTE"
                        docker tag "$CATALOG_IMAGE:$IMAGE_TAG" "$CATALOG_REMOTE"
                        docker tag "$ORDERS_IMAGE:$IMAGE_TAG" "$ORDERS_REMOTE"
                        docker tag "$INVENTORY_IMAGE:$IMAGE_TAG" "$INVENTORY_REMOTE"
                        docker push "$BACKEND_REMOTE"
                        docker push "$FRONTEND_REMOTE"
                        docker push "$CATALOG_REMOTE"
                        docker push "$ORDERS_REMOTE"
                        docker push "$INVENTORY_REMOTE"
                    '''
                }
            }
        }
        stage('Prepare reviewed GitOps release') {
            when {
                allOf {
                    branch 'main'
                    expression { params.PUBLISH_IMAGES }
                }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKERHUB_USERNAME', passwordVariable: 'DOCKERHUB_TOKEN')]) {
                    sh 'python3 scripts/prepare-release.py --registry "docker.io/$DOCKERHUB_USERNAME" --revision "$IMAGE_TAG" --branch main'
                }
                archiveArtifacts artifacts: 'deploy/releases/bookstore.json', fingerprint: true
                echo 'Review and commit the release manifest to Git, then apply it to Argo CD; CI does not mutate the cluster.'
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'compose-up.log', allowEmptyArchive: true
            sh 'docker image ls "$BACKEND_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$FRONTEND_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$CATALOG_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$ORDERS_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$INVENTORY_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            deleteDir()
        }
        success {
            echo 'Validation and deployment-artifact checks passed. Optional SonarQube, Trivy and registry publishing stages run only when explicitly configured.'
        }
        failure {
            echo 'Pipeline failed. Inspect the first failing stage and its console output.'
        }
    }
}
