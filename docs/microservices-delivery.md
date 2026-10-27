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

### Asynchronous checkout (transactional outbox)

Checkout is owned by the orders service and is **asynchronous**:

1. `POST /api/checkout` with a required `Idempotency-Key` (8-48 characters; the
   worker appends `:reserve`, keeping the inventory key within its 64 limit).
2. One database transaction writes the order (`PENDING`), the idempotency record
   (`checkout_attempts`) and an event in the transactional outbox
   (`checkout_outbox`). Either all three exist or none do, so the intent to
   reserve stock cannot be lost.
3. The service answers **202 Accepted** with `Idempotency-Replayed: false`. The
   browser polls `GET /api/orders/{id}` until the status leaves `PENDING`.
4. `CheckoutWorker` drains pending events every 2 s, calling
   `POST /api/inventory/{bookId}/reserve` with the derived key
   `<checkout-key>:reserve`. Inventory records that key, so retries after a
   timeout or crash replay the original result instead of reserving twice.
5. A definitive rejection (404 unknown book, 409 insufficient stock, 400 invalid
   quantity) cancels the order. Other failures are retried; after
   `checkout.worker.max-attempts` the attempt becomes `NEEDS_RECONCILIATION`.
6. `CheckoutReconciler` then settles those attempts **without a human**: it reads
   `GET /api/inventory/operations/{key}` from the inventory ledger.
   - the reservation is recorded -> order `CONFIRMED`;
   - no entry -> nothing was reserved, so the event is re-queued and the worker
     retries (bounded by `checkout.reconciler.max-cycles`);
   - still no entry after the last cycle -> order `CANCELLED`, which is safe
     precisely because the ledger proves no stock is held.

Replaying a checkout key never reserves again: a completed key returns the same
order, a failed key returns its original error, and a key awaiting reconciliation
returns 409 with a retry-later message. The browser no longer coordinates
inventory calls.

Worker settings (environment variables in Compose, Helm and EKS):

| Variable | Default | Meaning |
| --- | --- | --- |
| `CHECKOUT_WORKER_ENABLED` | `true` | Drain the outbox on this instance |
| `CHECKOUT_WORKER_DELAY_MS` | `2000` | Delay between batches |
| `CHECKOUT_WORKER_MAX_ATTEMPTS` | `5` | Attempts before reconciliation |
| `CHECKOUT_RECONCILER_ENABLED` | `true` | Settle unresolved attempts |
| `CHECKOUT_RECONCILER_DELAY_MS` | `5000` | Delay between reconciliation batches |
| `CHECKOUT_RECONCILER_MAX_CYCLES` | `3` | Re-queue rounds before cancelling |

Delivery is at-least-once and the only ordering guarantee is per event. The
opt-in payment, authorization and tracing pieces are described below; versioned
schema migrations and a production PSP adapter remain out of scope, so this is
still a **demo** workflow and not a payment product. Do not use it for real
orders.

### Payment authorization

Payment is the second step of the checkout saga and is **off by default**
(`payments.enabled=false` / `PAYMENTS_ENABLED`):

1. the worker reserves stock (idempotency key `<checkout-key>:reserve`);
2. it reads the unit price from the catalog service (server-side only - the
   browser never supplies an amount) and multiplies by the quantity;
3. it calls the `PaymentGateway` with `<checkout-key>:payment`;
4. approved -> order `CONFIRMED`; declined -> order `CANCELLED`, attempt
   `FAILED` (402) and a **durable** `RELEASE_STOCK` outbox event gives the stock
   back. Provider errors retry with the same key, then go to reconciliation.

There is no card data and no PSP integration in this repository: an adapter
implements `PaymentGateway` (`authorize` must be idempotent, `find` must report
what a key did). The reconciler asks the **payment record**, never the stock
ledger, before confirming an order whose checkout timed out - money is never
assumed. Enabling payments without a `PaymentGateway` bean fails at startup
instead of accepting unpayable orders.

`RELEASE_STOCK` events retry indefinitely on purpose: release is idempotent, so
the worst case is a delayed compensation rather than a double release.

### Authentication and authorization

Orders and inventory accept a bearer token when `security.jwt.enabled=true`
(`SECURITY_JWT_ENABLED`) with either `security.jwt.issuer-uri` (OIDC discovery)
or `security.jwt.jwk-set-uri`. Scopes:

| Scope | Grants |
| --- | --- |
| `orders.read` | `GET /api/orders*` |
| `orders.write` | `POST /api/checkout`, `POST`/`DELETE /api/orders*` |
| `inventory.read` | `GET /api/inventory*` |
| `inventory.write` | reserve/release |

Probes (`/actuator/health/**`), `/actuator/info` and `/actuator/prometheus` stay
public so Kubernetes and Prometheus need no credentials; everything else is
denied by default. A missing or invalid token is 401, a missing scope is 403.

The default is **off** so `make lab` works without an identity provider, and the
service logs a warning on every start while it is off. Enabling it without an
issuer or JWKS URI fails at startup. The frontend forwards
`sessionStorage.bookstoreToken` as `Authorization: Bearer ...` when present;
issuing tokens is the identity provider's job - there is no login screen here.

### Tracing, metrics and alerting

- Each service exposes Micrometer/Prometheus metrics on `/actuator/prometheus`.
- Distributed tracing uses Micrometer's OTLP bridge: set
  `observability.tracing.endpoint` (Helm) or `OTEL_EXPORTER_OTLP_ENDPOINT`, and
  `OTEL_SAMPLING_PROBABILITY` above `0` (default `0.0`) to record anything.
  Incoming requests inherit W3C `traceparent`, so browser -> nginx -> orders ->
  inventory is one trace.
- The orders service exports the two numbers that matter for the checkout
  pipeline: `checkout_outbox_pending` and
  `checkout_attempts_needs_reconciliation`.
- `monitoring/bookstore-dashboard.json` is a Grafana dashboard for the three
  services (outbox backlog, reconciliation, availability, 5xx ratio, p95,
  restarts) and `monitoring/slo-rules.yaml` adds availability and latency SLIs
  plus the checkout success ratio (`bookstore_checkout_confirmed_total` /
  `bookstore_checkout_cancelled_total`, exported by the orders service).
- `alerts.enabled=true` (orders chart) renders a PrometheusRule with
  `CheckoutOutboxBacklog`, `CheckoutReconciliationStuck` and
  `BookstoreApiServerErrors`; the response procedure is
  `docs/runbooks/checkout-reconciliation.md`.

### Service discovery

Discovery is Kubernetes-native: each chart creates a `ClusterIP` Service and the
callers use its DNS name (`http://inventory-service:8083`,
`http://catalog-service:8081`), resolved by CoreDNS inside the namespace.
Nothing else is required - no registry, no sidecar.

Cross-service URLs are configuration, never hard-coded, so the same images work
in Compose, kind and EKS:

| Variable | Default | Used by |
| --- | --- | --- |
| `INVENTORY_SERVICE_URL` | `http://inventory-service:8083` | orders worker |
| `CATALOG_SERVICE_URL` | `http://catalog-service:8081` | orders payment step |
| `SECURITY_JWT_ISSUER_URI` | empty | orders, inventory |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | local collector default | all services |

A service mesh (mTLS, retries, traffic shifting) is deliberately **not**
installed; if you add one, it replaces this DNS-based discovery and the
NetworkPolicy layer, not the application configuration.

### Production databases

The charts default to a PostgreSQL StatefulSet for the lab. For production, set
`database.deploy=false` and point the service at a managed instance:

```sh
helm upgrade --install orders-service helm/orders-service -n bookstore \
  --set database.deploy=false \
  --set database.host=bookstore.cluster-abc.ap-south-1.rds.amazonaws.com \
  --set database.port=5432 --set database.name=ordersdb \
  --set database.sslmode=require \
  --set database.existingSecret=orders-db-secret \
  --set database.hikari.maximumPoolSize=20
```

- `database.sslmode` is appended to the JDBC URL (`?sslmode=require`); use
  `verify-full` with the provider CA when available.
- `database.hikari.*` bounds the pool per pod: keep
  `replicas x maximumPoolSize` below the server's connection limit.
- The Secret must already exist (username/password keys); the chart never
  creates credentials for an external database.
- The services apply their own Flyway migrations at startup and run with
  `ddl-auto=validate`. Against a managed database set
  `FLYWAY_ENABLED=true` (default) and either let the service migrate, or run
  `flyway migrate` from CI with the same migrations and set
  `spring.flyway.enabled=false` at runtime. Snapshots taken *after* the
  migrations are the reference schema; `database/*-init.sql` remains only for
  the moment-zero local database.
- Managed providers give backups/PITR, failover and encryption; the scripts in
  `scripts/database-backup.sh` are for the lab only. Follow
  `docs/runbooks/postgres-backup-restore.md` for restore drills.

### Legacy data migration

The legacy Node API only owned a `books` table. `scripts/migrate-legacy.py`
copies those rows into the catalog database:

```sh
python3 scripts/migrate-legacy.py --dry-run   # print the SQL and the row count
python3 scripts/migrate-legacy.py --apply     # idempotent, single transaction
```

It matches on title+author, never updates or deletes catalog rows, refuses
oversized or malformed legacy rows, and reports how many rows were inserted.
Nothing is migrated for orders or inventory: those domains did not exist before
the split.

### Consumer-driven contract tests

`contracts/inventory-api.json` is the contract between orders-service (consumer)
and inventory-service (provider). Both services copy it onto their test classpath
(`<testResources>` in each pom) and assert against it, so a breaking rename fails
**both** builds:

- provider, `InventoryContractTest`: every documented endpoint exists, response
  field names and JSON types match, documented status codes are the ones
  actually returned, and a repeated `Idempotency-Key` does not consume stock;
- consumer, `InventoryClientContractTest`: the client sends exactly the
  documented path, query parameter and idempotency header, against a local HTTP
  stub - no inventory service needed.

Change the JSON first, then both sides, and bump `version`.

## Local stack

```sh
docker compose up --build -d --wait
curl -f http://localhost:8080/api/catalog/books
curl -f http://localhost:8080/api/orders
curl -f http://localhost:8080/api/inventory
```

Images for the three Java services build from the **repository root**
(`docker build -f services/<name>/Dockerfile .`) because their Maven build also
verifies `contracts/`, which lives outside the service directory. The frontend
keeps its own context. `docker compose build` and the `make lab` / `eks-images`
targets already pass the right context, so nothing to do by hand.

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

## Database schema and versioned migrations

Each service owns its schema and applies it with **Flyway** at startup
(`spring.flyway.enabled=true`, migrations in
`services/<name>/src/main/resources/db/migration`). Hibernate only validates
(`ddl-auto=validate`), so a migration that drifts from the entities fails the
deployment instead of silently reshaping tables.

```text
V1__catalog_schema.sql      tables
V2__seed_demo_catalog.sql   idempotent demo data
```

Rules for adding one:

1. never edit an applied migration - add `V3__...sql` with the next number;
2. keep every statement re-runnable (`IF NOT EXISTS`, `WHERE NOT EXISTS`),
   because lab databases may have been created from the older
   `database/*-init.sql` scripts;
3. `spring.flyway.baseline-on-migrate=true` baselines such a database at version
   0 and then applies the migrations;
4. the Compose stack ships **no** init scripts for the three service databases:
   CI proves the migrations work by starting empty databases and asserting
   `flyway_schema_history` reached the latest version.

`FLYWAY_ENABLED=false` exists only for troubleshooting. The `database/*.sql`
files stay in the repository as the reference schema for teams that create a
managed database by hand; the migrations are authoritative.

## Local identity provider (Keycloak)

`make auth` starts Keycloak on <http://localhost:8090> with the `bookstore` realm
imported from `deploy/local/auth/bookstore-realm.json`. It is a Compose
`auth` profile, off by default.

```sh
make auth                 # start Keycloak (admin / local-keycloak-admin)
make auth-token           # print a demo bearer token
export TOKEN=$(make -s auth-token)
```

To make the services enforce it (Compose):

```sh
SECURITY_JWT_ENABLED=true \
SECURITY_JWT_JWK_SET_URI=http://keycloak:8090/realms/bookstore/protocol/openid-connect/certs \
  docker compose up -d orders-service inventory-service
```

The realm grants `orders.read`, `orders.write`, `inventory.read` and
`inventory.write` as default client scopes, and declares a service account for
`orders-service`. In the browser, store the token with
`sessionStorage.setItem('bookstoreToken', '<token>')`; the frontend forwards it
on every request.

CI proves this path on every run: it starts the realm, reads a token with
`scripts/auth-token.sh`, restarts the orders service with enforcement on and
asserts `401` without a token, `200` with it, and that probes and metrics stay
public. Jenkins covers the unsecured lab path only.

Lab only: the demo user password is public and the JWKS URI trusts any token
signed by that realm (no issuer check). In a real environment use
`security.jwt.issuer-uri` and a managed identity provider.

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

## Inspecting unresolved checkouts

Reconciliation is automatic, so a stuck row means the *reconciler* could not
reach the inventory ledger (for example inventory was down for longer than you
tolerate). Rows left in these states are diagnostics, not a work queue:

```sql
SELECT idempotency_key, order_id, status, response_status, message
FROM checkout_attempts
WHERE status IN ('PENDING', 'NEEDS_RECONCILIATION')
ORDER BY idempotency_key;

SELECT aggregate_key, status, attempts, reconcile_cycles, last_error
FROM checkout_outbox
WHERE status <> 'DONE'
ORDER BY id;
```

Both workers are safe to restart at any time: a crash mid-call leaves the outbox
row `PENDING`, the retry reuses the same idempotency key and the inventory ledger
deduplicates it. To force a stuck checkout to be reconsidered, set its attempt
back to `NEEDS_RECONCILIATION`; the reconciler reads the ledger again and settles
it. **Do not** edit stock quantities by hand - the ledger is the source of truth
for whether a reservation landed.

Because the browser polls for confirmation, a checkout can stay `PENDING` for a
few seconds after a 202 response. That is expected, not an error.

This limitation set is why the demo is not suitable for real payments.
