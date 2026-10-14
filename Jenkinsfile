pipeline {
    agent any
    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 45, unit: 'MINUTES')
    }
    parameters {
        booleanParam(name: 'SONARQUBE_ENABLED', defaultValue: false, description: 'Requires SonarScanner and Jenkins credential sonarqube-token.')
        booleanParam(name: 'TRIVY_ENABLED', defaultValue: false, description: 'Requires Trivy installed on the agent.')
        booleanParam(name: 'PUBLISH_IMAGES', defaultValue: false, description: 'Publish immutable commit-tagged images to Docker Hub from main; requires dockerhub-creds.')
        booleanParam(name: 'PUBLISH_TO_ECR', defaultValue: false, description: 'Publish images to existing AWS ECR repositories from main.')
        string(name: 'AWS_REGION', defaultValue: 'ap-south-1', description: 'AWS region containing ECR repositories.')
        string(name: 'ECR_REGISTRY', defaultValue: '', description: 'ECR registry host, for example account-id.dkr.ecr.ap-south-1.amazonaws.com.')
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
                script { env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim() }
            }
        }
        stage('Validate frontend') {
            steps {
                dir('frontend') {
                    sh 'npm ci'
                    sh 'npm run lint'
                    sh 'npm run build'
                }
            }
        }
        stage('Validate Catalog microservice') {
            steps { dir('services/catalog-service') { sh 'mvn -B -ntp verify' } }
        }
        stage('Validate Orders microservice') {
            steps { dir('services/orders-service') { sh 'mvn -B -ntp verify' } }
        }
        stage('Validate Inventory microservice') {
            steps { dir('services/inventory-service') { sh 'mvn -B -ntp verify' } }
        }
        stage('SonarQube analysis') {
            when { expression { params.SONARQUBE_ENABLED } }
            steps {
                withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                    sh 'test -n "$SONAR_HOST_URL" && command -v sonar-scanner'
                    sh 'sonar-scanner -Dsonar.host.url="$SONAR_HOST_URL" -Dsonar.token="$SONAR_TOKEN"'
                }
            }
        }
        stage('Build images') {
            steps {
                sh 'docker build -t $FRONTEND_IMAGE:$IMAGE_TAG ./frontend'
                sh 'docker build -t $CATALOG_IMAGE:$IMAGE_TAG ./services/catalog-service'
                sh 'docker build -t $ORDERS_IMAGE:$IMAGE_TAG ./services/orders-service'
                sh 'docker build -t $INVENTORY_IMAGE:$IMAGE_TAG ./services/inventory-service'
            }
        }
        stage('Trivy image scan') {
            when { expression { params.TRIVY_ENABLED } }
            steps {
                sh '''
                    set -eu
                    command -v trivy
                    for image in "$FRONTEND_IMAGE" "$CATALOG_IMAGE" "$ORDERS_IMAGE" "$INVENTORY_IMAGE"; do
                      trivy image --exit-code 1 --severity HIGH,CRITICAL --ignore-unfixed "$image:$IMAGE_TAG"
                    done
                '''
            }
        }
        stage('Helm chart validation') {
            steps {
                sh '''
                    set -eu
                    command -v helm
                    for chart in frontend catalog-service orders-service inventory-service; do
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
                        if [ "$attempt" -ge 30 ]; then docker compose logs; exit 1; fi
                        sleep 3
                      done
                    done
                    curl --fail --silent http://localhost:8080/ >/dev/null
                    curl --fail --silent http://localhost:8080/api/catalog/books | grep -q 'Kubernetes Basics'
                    curl --fail --silent http://localhost:8080/api/orders | grep -q 'CREATED'
                    curl --fail --silent http://localhost:8080/api/inventory | grep -q 'availableQuantity'
                '''
            }
            post {
                always {
                    sh 'docker compose logs --no-color || true'
                    sh 'docker compose down -v --remove-orphans || true'
                }
            }
        }
        stage('Publish images to ECR') {
            when { allOf { expression { params.PUBLISH_TO_ECR }; branch 'main' } }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-credentials']]) {
                    sh '''
                        set -eu
                        test -n "$ECR_REGISTRY"
                        aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY"
                        trap 'docker logout "$ECR_REGISTRY" >/dev/null 2>&1 || true' EXIT
                        for item in "FRONTEND_IMAGE:frontend" "CATALOG_IMAGE:catalog-service" "ORDERS_IMAGE:orders-service" "INVENTORY_IMAGE:inventory-service"; do
                          var="${item%%:*}"; repo="${item#*:}"
                          eval "image=\$$var"
                          docker tag "$image:$IMAGE_TAG" "$ECR_REGISTRY/bookstore-lab/$repo:$IMAGE_TAG"
                          docker push "$ECR_REGISTRY/bookstore-lab/$repo:$IMAGE_TAG"
                        done
                    '''
                }
            }
        }
        stage('Publish images to Docker Hub') {
            when { allOf { expression { params.PUBLISH_IMAGES }; branch 'main' } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKERHUB_USERNAME', passwordVariable: 'DOCKERHUB_TOKEN')]) {
                    sh '''
                        set -eu
                        echo "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USERNAME" --password-stdin
                        trap 'docker logout >/dev/null 2>&1 || true' EXIT
                        for image in "$FRONTEND_IMAGE" "$CATALOG_IMAGE" "$ORDERS_IMAGE" "$INVENTORY_IMAGE"; do
                          remote="docker.io/$DOCKERHUB_USERNAME/$image:$IMAGE_TAG"
                          docker tag "$image:$IMAGE_TAG" "$remote"
                          docker push "$remote"
                        done
                    '''
                }
            }
        }
    }
    post {
        always {
            sh 'docker image ls "bookstore-*" --format "{{.Repository}}:{{.Tag}}" || true'
            cleanWs()
        }
        success { echo 'Microservices validation and deployment-artifact checks passed.' }
    }
}
