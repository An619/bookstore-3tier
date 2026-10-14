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

        stage('Maven catalog service validation') {
            steps {
                dir('services/catalog-service') {
                    sh 'java -version'
                    sh '''
                        for attempt in 1 2 3; do
                          mvn -B -ntp verify && exit 0
                          echo "Maven attempt $attempt failed; retrying"
                          sleep $((attempt * 5))
                        done
                        exit 1
                    '''
                }
            }
        }

        stage('Orders microservice validation') {
            steps {
                dir('services/orders-service') {
                    sh 'mvn -B -ntp verify'
                }
            }
        }

        stage('Inventory microservice validation') {
            steps {
                dir('services/inventory-service') {
                    sh 'mvn -B -ntp verify'
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
                sh 'docker build -t $CATALOG_IMAGE:$IMAGE_TAG ./services/catalog-service'
                sh 'docker build -t $ORDERS_IMAGE:$IMAGE_TAG ./services/orders-service'
                sh 'docker build -t $INVENTORY_IMAGE:$IMAGE_TAG ./services/inventory-service'
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
                '''
            }
        }

        stage('Compose integration smoke test') {
            steps {
                sh 'docker compose up --build -d'
                sh '''
                    set -eu
                    attempt=0
                    until curl --fail --silent http://localhost:5000/api/ready; do
                      attempt=$((attempt + 1))
                      if [ "$attempt" -ge 20 ]; then
                        docker compose logs
                        exit 1
                      fi
                      sleep 3
                    done
                    curl --fail --silent http://localhost:5000/api/books | grep -q "DevOps for Beginners"
                    curl --fail --silent http://localhost:8080/ > /dev/null
                    curl --fail --silent http://localhost:8080/api/catalog/books | grep -q "Kubernetes Basics"
                    attempt=0
                    until curl --fail --silent http://localhost:8081/actuator/health/readiness | grep -q '"status":"UP"'; do
                      attempt=$((attempt + 1))
                      if [ "$attempt" -ge 20 ]; then docker compose logs; exit 1; fi
                      sleep 3
                    done
                    curl --fail --silent http://localhost:8081/api/catalog/books | grep -q "Kubernetes Basics"
                    attempt=0
                    until curl --fail --silent http://localhost:8082/actuator/health/readiness | grep -q '"status":"UP"'; do
                      attempt=$((attempt + 1)); if [ "$attempt" -ge 20 ]; then docker compose logs; exit 1; fi; sleep 3
                    done
                    attempt=0
                    until curl --fail --silent http://localhost:8083/actuator/health/readiness | grep -q '"status":"UP"'; do
                      attempt=$((attempt + 1)); if [ "$attempt" -ge 20 ]; then docker compose logs; exit 1; fi; sleep 3
                    done
                    curl --fail --silent http://localhost:8082/api/orders | grep -q "CREATED"
                    curl --fail --silent http://localhost:8083/api/inventory | grep -q "availableQuantity"
                    curl --fail --silent http://localhost:8080/api/orders | grep -q "CREATED"
                    curl --fail --silent http://localhost:8080/api/inventory | grep -q "availableQuantity"
                '''
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
    }

    post {
        always {
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
