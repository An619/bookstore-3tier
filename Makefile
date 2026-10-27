# Shortcuts for the local lab and the repository checks.
# The lab runs on your machine only; it creates no cloud resources.
SHELL := /bin/sh
LAB := sh scripts/lab.sh
CI_LAB := sh deploy/local/ci/lab.sh

.DEFAULT_GOAL := help

.PHONY: help lab lab-ci lab-url lab-status lab-logs lab-down ci-status ci-run ci-down ci-reset test verify frontend preflight

help: ## Show the available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-14s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

lab: ## Tools + kind cluster + images + deploy + smoke test
	$(LAB) up --open

lab-ci: ## Application lab plus Jenkins and SonarQube (slow on first run)
	$(LAB) up --ci

lab-argocd: ## Deploy through Argo CD instead of Helm (needs kubectl)
	$(LAB) up --argocd

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

preflight: ## Check which local tools are installed
	sh scripts/preflight.sh

test: ## Run the Python validation tests
	python3 -m unittest discover -s tests -v

frontend: ## Lint and build the frontend
	cd frontend && npm ci && npm run lint && npm run build

verify: test frontend ## Everything that can run without Docker or Java
	mvn -B -ntp verify
