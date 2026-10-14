pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 45, unit: 'MINUTES')
    }

    parameters {
        booleanParam(name: 'SONARQUBE_ENABLED', defaultValue: false, description: 'Requires SonarScanner, SONAR_HOST_URL and Jenkins credential sonarqube-token.')
        booleanParam(name: 'TRIVY_ENABLED', defaultValue: false, description: 'Requires Trivy installed on the Jenkins agent.')
        booleanParam(name: 'PUBLISH_IMAGES', defaultValue: false, description: 'Publish commit-tagged images to Docker Hub from main; requires dockerhub-creds.')
        booleanParam(name: 'PUBLISH_TO_ECR', defaultValue: false, description: 'Publish commit-tagged images to AWS ECR from main; requires AWS credentials and existing ECR repositories.')
        string(name: 'AWS_REGION', defaultValue: 'ap-south-1', description: 'AWS region containing the ECR repositories.')
        string(name: 'ECR_REGISTRY', defaultValue: '', description: 'ECR registry host, for example account-id.dkr.ecr.ap-south-1.amazonaws.com.')
        string(name: 'ECR_FRONTEND_REPOSITORY', defaultValue: 'bookstore-lab/frontend', description: 'Frontend ECR repository name.')
        string(name: 'ECR_CATALOG_REPOSITORY', defaultValue: 'bookstore-lab/catalog-service', description: 'Catalog ECR repository name.')
        string(name: 'ECR_ORDERS_REPOSITORY', defaultValue: 'bookstore-lab/orders-service', description: 'Orders ECR repository name.')
        string(name: 'ECR_INVENTORY_REPOSITORY', defaultValue: 'bookstore-lab/inventory-service', description: 'Inventory ECR repository name.')
    }

    environment {
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

        stage('Catalog microservice validation') {
            steps {
                dir('services/catalog-service') {
                    sh 'java -version'
                    sh 'mvn -B -ntp verify'
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
                        sonar-scanner -Dsonar.host.url="$SONAR_HOST_URL" -Dsonar.token="$SONAR_TOKEN"
                    '''
                }
            }
        }

        stage('Build container images') {
            steps {
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
                    for chart in catalog-service orders-service inventory-service; do
                      helm lint "./helm/$chart"
                      helm template "$chart" "./helm/$chart" --namespace bookstore > "/tmp/$chart-rendered.yaml"
                      test -s "/tmp/$chart-rendered.yaml"
                    done
                '''
            }
        }

        stage('Compose integration smoke test') {
            steps {
                sh 'docker compose up --build -d'
                sh '''
                    set -eu
                    for port in 8081 8082 8083; do
                      attempt=0
                      until curl --fail --silent "http://localhost:$port/actuator/health/readiness" | grep -q '"status":"UP"'; do
                        attempt=$((attempt + 1))
                        if [ "$attempt" -ge 40 ]; then docker compose logs; exit 1; fi
                        sleep 3
                      done
                    done
                    curl --fail --silent http://localhost:8080/nginx-health | grep -q 'ok'
                    curl --fail --silent http://localhost:8080/api/catalog/books | grep -q "Kubernetes Basics"
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
                    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"
                    trap 'docker logout "$ECR_REGISTRY" >/dev/null 2>&1 || true' EXIT
                    for item in "FRONTEND:$FRONTEND_IMAGE:$ECR_FRONTEND_REPOSITORY" "CATALOG:$CATALOG_IMAGE:$ECR_CATALOG_REPOSITORY" "ORDERS:$ORDERS_IMAGE:$ECR_ORDERS_REPOSITORY" "INVENTORY:$INVENTORY_IMAGE:$ECR_INVENTORY_REPOSITORY"; do
                      image_name=$(printf '%s' "$item" | cut -d: -f2)
                      repo_name=$(printf '%s' "$item" | cut -d: -f3)
                      remote="$ECR_REGISTRY/$repo_name:$IMAGE_TAG"
                      docker tag "$image_name:$IMAGE_TAG" "$remote"
                      docker push "$remote"
                    done
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
                        echo "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USERNAME" --password-stdin
                        trap 'docker logout >/dev/null 2>&1 || true' EXIT
                        for image_name in "$FRONTEND_IMAGE" "$CATALOG_IMAGE" "$ORDERS_IMAGE" "$INVENTORY_IMAGE"; do
                          remote="docker.io/$DOCKERHUB_USERNAME/$image_name:$IMAGE_TAG"
                          docker tag "$image_name:$IMAGE_TAG" "$remote"
                          docker push "$remote"
                        done
                    '''
                }
            }
        }
    }

    post {
        always {
            sh 'docker image ls "$FRONTEND_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$CATALOG_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$ORDERS_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            sh 'docker image ls "$INVENTORY_IMAGE" --format "{{.Repository}}:{{.Tag}}" || true'
            deleteDir()
        }
        success {
            echo 'Microservices validation and deployment-artifact checks passed. Optional SonarQube, Trivy and registry publishing stages require explicit configuration.'
        }
        failure {
            echo 'Pipeline failed. Inspect the first failing stage and its console output.'
        }
    }
}
