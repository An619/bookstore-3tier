# Microservices build and delivery

## Topology

```text
Browser -> React / Nginx (frontend)
              | /api/catalog/* -> Catalog:8081 -> catalog PostgreSQL
              | /api/checkout   -> Orders:8082 ----reserve/release--> Inventory:8083
              | /api/orders*    -> Orders:8082 -> orders PostgreSQL
              | /api/inventory* -> Inventory:8083 -> inventory PostgreSQL

Git -> Jenkins -> Maven tests + JaCoCo -> SonarQube quality gate
              -> Docker images -> smoke tests -> registry (commit SHA tags)
              -> reviewed release manifest -> Git -> Argo CD -> Kubernetes
```

Each domain owns its data; no service queries another service's database.
Checkout is owned by the orders service: `POST /api/checkout` reserves stock,
creates the order and releases stock if order creation fails. A required
`Idempotency-Key` header (8-64 characters) makes retries safe - a repeated key
returns the original order instead of reserving twice. The browser no longer
coordinates inventory calls.

Remaining limits: this is a **demo** workflow, not a production payment or saga
implementation. A crash after reserving stock but before the order is stored
leaves stock reserved, and such attempts are recorded as
`NEEDS_RECONCILIATION` and refused rather than retried. There is no automatic
reconciliation worker, no payment step, no authentication/authorization and no
distributed transaction. Do not use it for real orders.

## Local stack

```sh
docker compose up --build -d --wait
curl -f http://localhost:8080/api/catalog/books
curl -f http://localhost:8080/api/orders
curl -f http://localhost:8080/api/inventory
```

The default stack is frontend, three services and three databases. The old Node
API and its database are available only with `docker compose --profile legacy
up --build -d`; legacy endpoints are direct on port 5000, not routed by Nginx.
Stop with `docker compose down` (preserves data). Never use `down -v` on data you
need to keep. Existing legacy data is **not** automatically migrated.

For source development use Java 21, Maven 3.9+, Node 22, Python 3 and Docker:

```sh
mvn -B -ntp verify                       # all three services + JaCoCo XML
mvn -B -ntp -pl services/orders-service verify  # one service
python3 -m unittest discover -s tests -v
cd frontend && npm ci && npm run dev
```

Vite proxies domain APIs server-side to service ports 8081–8083; browsers use
same-origin URLs. Kubernetes uses the same domain paths through Nginx.

## Checkout schema upgrade

Orders persists idempotency outcomes in `checkout_attempts`. The init scripts
(`database/orders-service-init.sql` and the orders chart ConfigMap) only run on a
**fresh** PostgreSQL data directory. For an existing database or persistent
volume, apply this once before deploying the new orders image:

```sql
CREATE TABLE IF NOT EXISTS checkout_attempts (
  idempotency_key VARCHAR(64) PRIMARY KEY,
  order_id BIGINT,
  status VARCHAR(32) NOT NULL,
  response_status INTEGER NOT NULL,
  message VARCHAR(200)
);
```

The service validates its schema at startup (`ddl-auto=validate`), so it fails
fast instead of silently creating tables. A real deployment should manage this
with versioned migrations.

## Local Kubernetes lab

Two commands run everything locally (details in `docs/QUICKSTART.md` and
`deploy/local/README.md`):

```sh
make lab      # tools + kind cluster + images + deploy + smoke test
make lab-ci   # plus Jenkins and SonarQube, configured automatically
```

`scripts/lab.sh` downloads a checksum-verified `kind`, runs Helm from a pinned
container image when Helm is absent, generates database passwords into an
ignored 0600 file, deploys with `--set-file` (passwords never appear in the
process list) and exposes the app on a fixed NodePort. `deploy/local/ci/lab.sh`
provisions SonarQube (project, quality gate, token) through its API and starts
Jenkins with configuration-as-code plus the `bookstore-pipeline` job, so no
setup wizard or manual credential entry is needed. `kubectl` is optional and
only required for Argo CD, logs and status.

## Jenkins and SonarQube

Use the root Jenkinsfile in a multibranch job. Agents need Java 21, Maven 3.9+,
Node 22/npm, Python 3, Docker/Compose, curl and Helm 3; optional Trivy, AWS CLI,
and SonarScanner CLI must be installed when their stages are enabled.

Configure Jenkins secret-text credential `sonarqube-token`, set agent environment
`SONAR_HOST_URL` to your SonarQube URL and enable `SONARQUBE_ENABLED`. Analysis
runs after Maven generates class files and JaCoCo reports. Scanner waits for the
server's quality gate; failure blocks image build/publishing. Set the desired
coverage/issue thresholds on the `bookstore-3tier` project in SonarQube. Without
this parameter enabled there is no Sonar quality gate (appropriate only for local
learning). Configure a matching SonarScanner/server version for your installation.

For Docker Hub publishing, configure username/password credential
`dockerhub-creds` (use an access token as password) and enable `PUBLISH_IMAGES`.
Only main builds publish. Images are tagged with the 12-character source SHA.
The pipeline then archives `deploy/releases/bookstore.json`; it never pushes a
Git change or deploys directly. ECR publishing is retained; generate an ECR
release manually using the matching namespace (e.g. registry/bookstore-lab would
not match the existing ECR repository names: use explicit Helm image overrides
for those repositories).

## Reviewed GitOps deployment

For a local cluster all of this is automated: `make lab` deploys with Helm and
`make lab-argocd` does the Argo CD variant. The steps below apply to a shared or
remote cluster.

1. Install Argo CD separately in the cluster and configure access to this Git repo
   and the image registry. Configure registry pull credentials on the namespace's
   service account if images are private. Enable a default StorageClass.
2. Create namespace `bookstore` and Secrets `catalog-db-secret`,
   `orders-db-secret`, `inventory-db-secret`, each with `username` and `password`
   keys, using a secret manager or another approved secure workflow. The username
   for catalog is `cataloguser`; for orders/inventory it is `serviceuser`.
3. Download the Jenkins release artifact, or generate one for **published** images:

   ```sh
   python3 scripts/prepare-release.py --registry docker.io/YOUR_ACCOUNT \
     --revision YOUR_12_CHARACTER_SHA --branch arena/22830f5b-bookstore-3tier
   ```

   Replace placeholders (registry/account must be lowercase). Set `--branch` to
   the branch holding the reviewed charts: this session branch until merged,
   then `main`. Review and commit `deploy/releases/bookstore.json` through your
   normal review workflow. This file is JSON, also valid Kubernetes manifest input.
4. Apply only that reviewed release: `kubectl apply -f deploy/releases/bookstore.json`.
   It creates four Argo CD Applications. Do **not** also apply the legacy
   `argocd/bookstore-application.yaml` or install `helm/bookstore`.
5. Sync Catalog, Orders and Inventory first. Once healthy, sync frontend in Argo CD.
   Manual sync is intentional; enable automation only after reviewing storage,
   secret management and pruning policies.
6. Access with `kubectl -n bookstore port-forward svc/bookstore-frontend 8080:80`
   and open localhost:8080 on the machine running kubectl. For remote access,
   provision an ingress/TLS or a suitable load balancer separately.

Argo CD tracks chart revisions from Git and uses immutable image overrides from
the reviewed Application manifest. After each approved release, re-apply the
manifest to update those overrides (there is no app-of-apps controller here).
To roll back, restore the previous release manifest and apply/sync it; do not
roll back database data blindly. Keep release history in Git.

The three bundled PostgreSQL StatefulSets are for a learning cluster: production
needs managed databases or a database operator, backups and restore tests, TLS,
network policies, authentication, observability and independent schema migrations.

## Deployment hardening added

- `sh scripts/preflight.sh` checks tool availability, Docker daemon and cluster
  connectivity without printing credential values; exits nonzero if unavailable.
- `python3 scripts/smoke-test.py --base-url http://localhost:8080` waits for all
  domain APIs and exercises validation, reserve, create/read order, stock release
  and order deletion. It deletes the diagnostic order and restores stock even
  when an assertion fails; if cleanup fails it reports that explicitly.
  **Disposable environments only** — do not point it at production.
- CI fails on npm high/critical advisories. Maven reports are archived by Jenkins
  and GitHub Actions.
- Inventory writes now lock the stock row within a transaction, preventing
  concurrent read/modify/write overselling. Tests cover contention, invalid
  quantities and integer overflow; execute them with Maven on Java 21.
- All application pods disable automatic Kubernetes API-token mounting and have
  startup probes; private image pull secrets are configurable per chart.
- Services expose Micrometer/Prometheus metrics at `/actuator/prometheus`, and
  each chart has an opt-in Prometheus Operator ServiceMonitor. See
  `monitoring/README.md`; scraping also needs `networkPolicy.metricsNamespace`
  when NetworkPolicy is enabled.
- `deploy/examples/frontend-values.yaml` illustrates ingress/TLS and network
  isolation. Set real DNS/TLS values. Install an ingress controller separately;
  create a TLS Secret through your certificate manager. No certificates are
  generated or committed here.
- Each chart has opt-in `networkPolicy.enabled`. Enable it on **all four** charts
  after verifying your CNI enforces NetworkPolicy. APIs accept only the frontend;
  each database accepts only its owning API; APIs allow DNS and their own DB.
  The frontend web port is public, while its outbound connections are limited to
  DNS and domain APIs. Adapt DNS policies for NodeLocal DNS if used. Database
  isolation blocks ad-hoc backup/monitoring pods until explicitly allowed.

When using generated Argo Applications, add reviewed Helm `parameters` or
`valuesObject` to each Application for these settings; the frontend example is
not automatically included. Network policy is defense-in-depth, **not user
identity or authorization**. Do not expose this demo to the public Internet
without implementing and testing authentication and a durable checkout workflow.

## Practising and deploying

- Browser VM (no local Docker): `docs/killercoda.md`, using `make lab-light`.
- AWS EKS: `docs/eks.md` with `make eks-plan|eks-apply|eks-images|eks-deploy|
  eks-release|eks-status|eks-destroy`. No EBS is required: the default
  `EKS_DB_STORAGE=ephemeral` uses emptyDir, and `pvc`/`external` cover durable
  options. ECR names are
  `<project>-<environment>-<component>`; pass the same prefix to
  `scripts/prepare-release.py --repository-prefix` (the Makefile target does).

## External work still requires access

This checkout does not supply a running Jenkins controller, SonarQube server,
container registry credentials, Kubernetes context, DNS zone or certificate
issuer. Those cannot be provisioned or verified just by editing the repository.
Use the configuration steps above in an authorized environment. No public
exposure, cloud resources, or deployment is performed automatically.

## Reconciling interrupted checkouts

`checkout_attempts` never hides a possible double effect. Inspect unresolved
attempts in the orders database (read-only query):

```sql
SELECT idempotency_key, order_id, status, response_status, message
FROM checkout_attempts
WHERE status IN ('PENDING', 'NEEDS_RECONCILIATION')
ORDER BY idempotency_key;
```

For each row, compare the inventory service's stock with the expected value and
decide whether stock must be released or an order recorded. Then resolve the row
manually (for example set `status = 'FAILED'` after releasing stock, or attach the
confirmed `order_id` and set `status = 'COMPLETED'`). There is **no** automation
for this; it is an operator action. A `PENDING` row means a checkout request did
not finish (for example the pod was killed) - confirm what the inventory service
actually did before retrying with the same key.

This limitation is why the demo is not suitable for real payments.
