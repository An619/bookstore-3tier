# Quick start: the fewest possible steps

> **Your plan:** practise on Killercoda (`docs/killercoda.md`), then move to AWS
> EKS (`docs/eks.md`). This page is the local-machine version they build on.

Two commands get the whole application running locally. Prerequisites: Docker
Engine or Docker Desktop (running), `git`, `python3` and `curl`. `kind` is
downloaded automatically (checksum-verified); Helm runs through its pinned
container image if you do not have it; `kubectl` is optional.

```sh
git clone https://github.com/An619/bookstore-3tier.git
cd bookstore-3tier
make lab
```

`make lab` creates a disposable kind cluster, builds the four images, generates
database passwords (kept out of Git), deploys the charts, waits for readiness,
runs an end-to-end checkout smoke test, and prints <http://localhost:8080>.

Add Jenkins and SonarQube with one more command (first run pulls several
images and can take 5-10 minutes):

```sh
make lab-ci
```

That single command generates all passwords, starts SonarQube, creates the
project, a new-code coverage quality gate and an API token, starts Jenkins with
configuration-as-code (admin user, SonarQube server, token credential) and
creates the `bookstore-pipeline` job. Then run `make ci-run` to execute it.

## Everyday commands

| Command | What it does |
| --- | --- |
| `make lab` | Bring the application up (or update it after code changes) |
| `make lab-light` | Same, with small requests/storage for a 2 CPU / 4 GB machine |
| `make lab-verify` | Prove the deployment: pods, migrations, checkout saga, pod-kill recovery |
| `make lab-status` / `make lab-logs` | Inspect the cluster / follow logs |
| `make lab-down` | Delete the lab cluster and its data |
| `make lab-argocd` | Deploy through Argo CD instead of Helm (`kubectl` required) |
| `make lab-ci` | Start Jenkins + SonarQube (or reuse existing data) |
| `make auth` / `make auth-token` | Start the local Keycloak realm / print a demo JWT |
| `make auth-cluster` / `make auth-cluster-secure` | Run that realm inside the cluster and turn enforcement on |
| `make ci-run` | Trigger the pipeline once |
| `make lab-ci-run` | Do both of the above in one command |
| `make ci-status` | Show the lab URLs and generated passwords |
| `make ci-reset` | Delete Jenkins/SonarQube data and start over |
| `make verify` | Run the tests that need no Docker (Python, frontend, Maven) |

## Did it actually work?

`make lab` ends with a smoke test, but a pass does not tell you whether a pod is
restarting or a database is on an older migration. One command answers it:

```sh
make lab-verify                                   # adds --resilience
sh scripts/verify-deploy.sh --skip-smoke          # quick re-check, no order written
sh scripts/verify-deploy.sh --url http://<host>   # any reachable entry point
```

It checks, in order: every rollout completes; no pod is unready, crash-looping or
restarting more than `--max-restarts` (default 0) times; `flyway_schema_history`
reaches the expected version in each service database (external databases are
skipped, not failed); the end-to-end checkout smoke test passes through the public
entry point; and with `--resilience`, deleting the orders pod does not break the
API. Exit code 0 means every executed check passed, so it is safe to use in a
pipeline.

## Practise, then deploy for real

1. **Killercoda** — `docs/killercoda.md` has the exact clone, install and
   `make lab-light` steps for the browser VM, plus what cannot be practised there.
2. **EKS** — `docs/eks.md`: fill `terraform/eks/terraform.tfvars`, then
   `make eks-plan`, `eks-apply`, `eks-images`, `eks-deploy`, `eks-verify`,
   `eks-status`, and
   `eks-destroy` when finished. No EBS is required: the default
   `EKS_DB_STORAGE=ephemeral` runs the databases without volumes, and `pvc` or
   `external` give you durable options. The AWS bill (EKS control plane, nodes,
   NAT gateway, load balancer) is itemised in `docs/aws-costs.md` — budget roughly
   $190-205/month if you leave the default lab running, ~$0.26/hour.

## Things you still have to decide

These are choices or credentials that a repository cannot supply:

1. **Where it runs.** The lab is a single local node. For a shared cluster,
   install Argo CD, create the three database Secrets and a StorageClass, then
   apply the reviewed `deploy/releases/bookstore.json` (see
   `docs/microservices-delivery.md`).
2. **Image registry.** Images are built locally for the lab. Publishing needs
   your Docker Hub or ECR credentials in Jenkins plus `PUBLISH_IMAGES`.
3. **DNS and TLS.** Point a real host name at an ingress controller and supply a
   certificate; the example values use `bookstore.example.com`.
4. **Authentication.** The app ships with JWT enforcement **off** so the lab
   works without an identity provider. Before exposing it, set
   `security.jwt.enabled=true` with an issuer or JWKS URI (orders and inventory
   chart values) and hand clients a token with the documented scopes. There is
   still no login UI: tokens come from your identity provider.
5. **Payments.** `payments.enabled=false`. Turning it on requires a PSP adapter
   implementing `PaymentGateway`; no card data is handled by these services.

## Troubleshooting

- `Docker daemon is not reachable` — start Docker Desktop/Engine and retry.
- Port 8080 already in use — `sh scripts/lab.sh up --port 8091`.
- Failed while pulling images — retry; the lab reuses the cluster and volumes.
- Schema — the service databases are created by Flyway on first start; if a
  service exits with a validation error, check `flyway_schema_history` and the
  migrations under `services/*/src/main/resources/db/migration`.
- Ports — the app lab uses 8080 and the Jenkins pipeline publishes the Compose
  stack on 18080-18083, so both can run at the same time.
- Start over: `make lab-down && make ci-reset`, then `make lab`.
