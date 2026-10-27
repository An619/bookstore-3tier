# Shortcuts for the local lab and the repository checks.
# The lab runs on your machine only; it creates no cloud resources.
SHELL := /bin/sh
LAB := sh scripts/lab.sh
CI_LAB := sh deploy/local/ci/lab.sh

.DEFAULT_GOAL := help

.PHONY: help migrate-legacy auth auth-token lab lab-light lab-ci lab-ci-run lab-url lab-status lab-logs lab-down eks-plan eks-apply eks-images eks-deploy eks-release eks-status eks-uninstall eks-destroy ci-status ci-run ci-down ci-reset test verify frontend preflight

migrate-legacy: ## Copy legacy books into the catalog DB (dry run first: DRY=1)
	@if [ "$(DRY)" = "1" ]; then \
		python3 scripts/migrate-legacy.py --dry-run; \
	else \
		python3 scripts/migrate-legacy.py --apply; \
	fi

auth: ## Start the local Keycloak dev identity provider (port 8090)
	docker compose --profile auth up -d keycloak
	@printf 'Keycloak: http://localhost:8090 (admin / $${KEYCLOAK_ADMIN_PASSWORD:-local-keycloak-admin})\nGet a token with: make auth-token\n'

auth-token: ## Print a demo bearer token from the local Keycloak
	@sh scripts/auth-token.sh

help: ## Show the available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-14s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

lab: ## Tools + kind cluster + images + deploy + smoke test
	$(LAB) up --open

lab-ci: ## Application lab plus Jenkins and SonarQube (slow on first run)
	$(LAB) up --ci

lab-argocd: ## Deploy through Argo CD instead of Helm (needs kubectl)
	$(LAB) up --argocd

lab-ci-run: ## Start Jenkins/SonarQube (if needed) and trigger the pipeline
	$(LAB) up --ci
	$(CI_LAB) run

lab-url: ## Print the application URL
	$(LAB) url

lab-status: ## Show cluster and pod status
	$(LAB) status

lab-logs: ## Follow the frontend logs (override with POD=name)
	$(LAB) logs $(if $(POD),$(POD),)

lab-down: ## Delete the lab cluster and its data
	$(LAB) down

ci-status: ## Show Jenkins/SonarQube URLs
	$(CI_LAB) status

ci-run: ## Trigger the Jenkins pipeline
	$(CI_LAB) run

ci-down: ## Stop Jenkins/SonarQube (keeps their data)
	$(CI_LAB) down

ci-reset: ## Stop Jenkins/SonarQube and delete their data
	$(CI_LAB) reset

lab-light: ## Start the lab with small requests for a 2 CPU / 4 GB machine (Killercoda)
	sh scripts/lab.sh up --light --open

eks-plan: ## Terraform plan for the EKS lab (no changes)
	sh scripts/eks.sh plan

eks-apply: ## Create the EKS cluster, ECR repositories and EBS CSI driver (BILLABLE)
	sh scripts/eks.sh apply

eks-images: ## Build and push the four images tagged with this commit
	sh scripts/eks.sh images

eks-deploy: ## Create Secrets and install the charts from ECR images
	sh scripts/eks.sh deploy

eks-release: ## Write a reviewed Argo CD manifest for the pushed commit
	sh scripts/eks.sh release

eks-status: ## Show pods and the frontend load-balancer address
	sh scripts/eks.sh status

eks-uninstall: ## Remove Helm releases and the load balancer (run before eks-destroy)
	sh scripts/eks.sh uninstall

eks-destroy: ## Destroy the EKS cluster and all data (asks for confirmation)
	sh scripts/eks.sh destroy

preflight: ## Check which local tools are installed
	sh scripts/preflight.sh

test: ## Run the Python validation tests
	python3 -m unittest discover -s tests -v

frontend: ## Lint and build the frontend
	cd frontend && npm ci && npm run lint && npm run build

verify: test frontend ## Everything that can run without Docker or Java
	mvn -B -ntp verify
