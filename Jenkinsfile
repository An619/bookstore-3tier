pipeline {
  agent any

  parameters {
    choice(name: 'DEPLOY_ENV', choices: ['killercoda','eks'], description: 'Target Kubernetes environment')
    string(name: 'DOCKERHUB_USERNAME', defaultValue: 'An619', description: 'Docker Hub username')
    string(name: 'KUBE_NAMESPACE', defaultValue: 'bookstore', description: 'Kubernetes namespace')
  }

  environment {
    BACKEND_IMAGE = "\${params.DOCKERHUB_USERNAME}/bookstore-backend"
    FRONTEND_IMAGE = "\${params.DOCKERHUB_USERNAME}/bookstore-frontend"
    IMAGE_TAG = "build-\${BUILD_NUMBER}"
  }

  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Test Backend') {
      steps {
        dir('backend') {
          sh 'npm ci'
          sh 'npm test'
        }
      }
    }

    stage('Build Frontend') {
      steps {
        dir('frontend') {
          sh 'npm ci'
          sh 'npm run build'
        }
      }
    }

    stage('Build Images') {
      steps {
        sh 'docker build -t \${BACKEND_IMAGE}:\${IMAGE_TAG} ./backend'
        sh 'docker build -t \${FRONTEND_IMAGE}:\${IMAGE_TAG} ./frontend'
      }
    }

    stage('Push Images') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASSWORD')]) {
          sh 'echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USER" --password-stdin'
          sh 'docker push \${BACKEND_IMAGE}:\${IMAGE_TAG}'
          sh 'docker push \${FRONTEND_IMAGE}:\${IMAGE_TAG}'
          sh 'docker logout'
        }
      }
    }

    stage('Deploy') {
      steps {
        sh '''
          helm upgrade --install bookstore ./helm/bookstore \
            --namespace \${KUBE_NAMESPACE} \
            --create-namespace \
            -f ./helm/bookstore/values-\${DEPLOY_ENV}.yaml \
            --set backend.image.repository=\${BACKEND_IMAGE} \
            --set backend.image.tag=\${IMAGE_TAG} \
            --set frontend.image.repository=\${FRONTEND_IMAGE} \
            --set frontend.image.tag=\${IMAGE_TAG} \
            --wait --timeout 5m
        '''
      }
    }
  }

  post {
    always {
      sh 'docker image prune -f || true'
    }
  }
}
