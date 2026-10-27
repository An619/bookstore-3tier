# Local learning lab (kind + Helm or Argo CD, optional Jenkins/SonarQube)

Runs on your workstation; it is **not** a production design and creates no cloud
resources. The sandboxed agent cannot execute these steps because it has no
Docker runtime.

## Prerequisites

Docker Engine or Docker Desktop with Compose v2, `kind`, `kubectl`, `helm` 3,
`git`, `python3` and `openssl`. Allow roughly 6–8 GB free memory and 10 GB disk.
Verify tools and the Docker daemon:

```sh
sh scripts/preflight.sh
```

## Deploy the application to a disposable kind cluster

```sh
sh deploy/local/bootstrap.sh                 # Helm install (deterministic)
sh deploy/local/bootstrap.sh --argocd        # Argo CD install + automated sync
sh deploy/local/bootstrap.sh --argocd --revision arena/22830f5b-bookstore-3tier
```

The script:

1. Creates a `bookstore-lab` kind cluster (or reuses it).
2. Builds the four images from your working tree and loads them into kind.
3. Creates the `bookstore` namespace and randomly generated database Secrets.
   Existing Secrets are preserved on reruns.
4. Installs the charts with Helm, or installs Argo CD v3.5.6 and four
   Applications that automatically sync the chosen branch.
5. Waits for deployments, then runs the integration smoke test through a
   temporary port-forward. It creates one diagnostic order and restores stock.

Then open the app:

```sh
kubectl -n bookstore port-forward svc/bookstore-frontend 8080:80
# http://localhost:8080
```

Argo CD is optional. Its UI is at <https://localhost:8443> after
`kubectl -n argocd port-forward svc/argocd-server 8443:443`; the initial admin
password is in `argocd-initial-admin-secret`. Argo CD must reach GitHub to read
the chart sources and the branch must contain this work. `deploy/local/argocd/
applications.yaml` enables automated sync **for this disposable lab only**;
the repository `argocd/*.yaml` files remain manual-sync.

Images are built locally with tag `local` and never pushed. Pods use
`IfNotPresent`, so a rebuilt image requires re-running the build/load step
(`kind load docker-image ...`) and restarting the deployments.

## Ingress and TLS (optional)

The kind config maps host ports 8080/8443. Install an ingress controller
compatible with kind, then set values from `deploy/examples/frontend-values.yaml`
(real host name, an ingress class, and a TLS Secret you created). A local
self-signed certificate proves TLS wiring only; do not treat it as trusted.
Without ingress, use `kubectl port-forward` as above.

## Optional Jenkins and SonarQube lab

```sh
cd deploy/local/ci
cp .env.example .env
# Linux only: set DOCKER_GID to the value printed by:
stat -c %g /var/run/docker.sock
docker compose up -d --build
```

- Jenkins: <http://localhost:8085> (unlock with the initial admin password shown
  by `docker compose logs jenkins`).
- SonarQube: <http://localhost:9000> (`admin`/`admin` on first login; change it).

Then create a **multibranch pipeline** job pointing at your fork/repository with
the root `Jenkinsfile`. Add credentials `sonarqube-token` (created in SonarQube
under My Account → Security) and set `SONAR_HOST_URL` as an agent/global
environment variable. Enable `SONARQUBE_ENABLED` when you want the quality gate
to block builds. Set thresholds for project `bookstore-3tier` in SonarQube.
Docker Hub publishing needs `dockerhub-creds` and `PUBLISH_IMAGES`; it only runs
on `main`.

**Security warning:** mounting `/var/run/docker.sock` gives the Jenkins
controller effective root control of the host. Use only on a disposable
machine. Without the socket the pipeline's Compose stages cannot run. If Chrome
or the browser cannot reach Jenkins, keep it bound to localhost and do not
publish it.

## Troubleshooting

- `kind create cluster` fails: Docker must be running and the machine needs
  enough memory; delete stale clusters with `kind get clusters`.
- Pods stay `ImagePullBackOff`: run the bootstrap build/load again; the chart
  image names and `local` tag must match.
- Pods stay `Pending`: kind's default StorageClass must provision the database
  persistent volume claims; check `kubectl -n bookstore get pvc`.
- `port-forward` connection refused: frontend pods are not ready yet; inspect
  `kubectl -n bookstore get pods` and `kubectl -n bookstore describe pod ...`.
- NetworkPolicy is disabled by default in the lab; enable it only after
  confirming your CNI enforces it.
- Argo CD Applications stay `OutOfSync`/`Unknown`: the cluster needs outbound
  HTTPS to GitHub, and the branch must exist.

## Teardown

```sh
kind delete cluster --name bookstore-lab     # deletes all lab PVC data
cd deploy/local/ci && docker compose down -v # deletes Jenkins/Sonar data
```

The smoke test creates a demo order; the cluster is disposable. Never run it
against production.
