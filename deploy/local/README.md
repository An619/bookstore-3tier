# Local learning lab (kind + Helm or Argo CD, optional Jenkins/SonarQube)

Everything here runs on your workstation. It creates no cloud resources and
commits no secrets. The sandboxed agent cannot run it (no Docker runtime), so the
scripts are validated statically and by unit tests, not end to end.

Start with `docs/QUICKSTART.md`; this page explains what the automation does.

## What `make lab` automates

| Step | Detail |
| --- | --- |
| Tools | Uses your `kind` if present, otherwise downloads a pinned, SHA-256 verified binary into `.tools/bin`. Helm runs from its pinned container image unless installed. `kubectl` is optional. |
| Cluster | Creates the single-node `bookstore-lab` kind cluster and maps host `8080` (override with `--port`) to the frontend NodePort `30080`, so no port-forward is needed. |
| Images | Builds `bookstore-{frontend,catalog,orders,inventory}:local` from your working tree and loads them into the node. |
| Secrets | Generates random database passwords into `deploy/local/.secrets/lab.env` (mode 0600, git-ignored) and passes them to Helm with `--set-file`, so they never appear in the process list. Reruns reuse the same passwords. |
| Deploy | `helm upgrade --install` for the four charts into the `bookstore` namespace, with `--wait`. |
| Verify | Runs `scripts/smoke-test.py`: catalog/inventory/orders readiness, inventory validation, idempotent checkout, replay check, cleanup and routing. |

Re-running `make lab` rebuilds images, reloads them and upgrades the releases.
`make lab-down` deletes the cluster and therefore all lab data.

## Argo CD instead of Helm

`make lab-argocd` (requires `kubectl`) installs Argo CD v3.5.6, creates the
database Secrets, and applies the applications from
`deploy/local/argocd/applications.yaml` with automated sync of your current
branch. The repository is public, so no Git credentials are needed. The chart
release names are pinned to the Service DNS names Nginx expects.

The lab manifests enable automated sync/prune **for a disposable cluster only**.
The `argocd/*.yaml` files in the repository root stay manual-sync.

## Jenkins and SonarQube

```sh
make lab-ci
```

`deploy/local/ci/lab.sh` performs the whole setup:

1. Generates `deploy/local/ci/.env` (mode 0600) with the Docker socket group, a
   Jenkins admin password and a SonarQube database password.
2. Starts SonarQube and waits for it.
3. Uses the SonarQube API to set the admin password, create project
   `bookstore-3tier`, create the **Bookstore lab gate** (new-code coverage),
   assign it to the project and mint a token.
4. Stores the token in `.env` and starts Jenkins, which reads it through
   configuration-as-code: admin user, SonarQube server, `sonarqube-token`
   credential, no setup wizard.
5. An init script creates the `bookstore-pipeline` job for your branch.

`make ci-run` triggers that job. `make ci-status` prints the URLs and the
generated passwords; `make ci-reset` deletes the CI volumes and `.env` so the
next `make lab-ci` starts clean.

### Security warnings for the CI lab

- Mounting `/var/run/docker.sock` gives the Jenkins controller effective root
  access to your machine. Use a disposable machine or Docker Desktop you can
  reset.
- Ports 8085 (Jenkins) and 9000 (SonarQube) are published on all interfaces;
  keep them on a trusted network.
- If plugin installation fails while the image builds, the container still
  starts: configure credentials and the job manually. The build output contains
  a warning.
- The pipeline's Compose stage uses ports 8080-8083, which clash with the app
  lab. Stop one with `make lab-down` or `make ci-down` first.
- These images are pinned by tag, not digest. Do not reuse this setup beyond a
  local lab without pinning digests and adding authentication in front of
  Jenkins.

## Ingress and TLS (optional)

Host ports 8443 and node port 30443 are reserved for an ingress controller's
HTTPS listener. Install a kind-compatible controller, then set
`deploy/examples/frontend-values.yaml` values (host, ingress class, TLS Secret).
A locally generated certificate proves the wiring only; it is not trusted.

## Troubleshooting

- `Docker daemon is not reachable` — start Docker; `docker info` must work.
- Pods `ImagePullBackOff` — rerun `make lab` so images are rebuilt/loaded; the
  chart tag is `local` with `IfNotPresent`.
- Pods `Pending` — the kind default StorageClass must provision the database
  PVCs; check `make lab-status`.
- `make lab-argocd` hangs — the cluster needs outbound HTTPS to GitHub, and the
  branch must contain this work.
- `make lab-ci` fails at SonarQube — first boot is slow; rerun the command, it
  reuses volumes and only creates what is missing.
