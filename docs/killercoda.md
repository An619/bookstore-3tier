# Practice on Killercoda

Killercoda gives you a disposable Linux VM in the browser. Use the **Ubuntu**
playground (Docker available, root shell) rather than the Kubernetes playground:
the Kubernetes scene runs containerd without a Docker daemon, and this lab builds
its images locally.

Choose **Ubuntu 22.04** with at least 2 CPUs and 4 GB RAM if the wizard offers a
size. Sessions are time-limited and the VM is destroyed afterwards, so finish the
run in one session.

## Steps

```sh
# 1. Clone the branch under review
git clone --branch arena/22830f5b-bookstore-3tier \
  https://github.com/An619/bookstore-3tier.git
cd bookstore-3tier

# 2. Install the few missing tools (the image usually has git/curl/python3)
apt-get update && apt-get install -y --no-install-recommends make docker.io

# 3. Confirm Docker works
docker info >/dev/null && echo "Docker OK"
sh scripts/preflight.sh || true   # tells you what is missing/auto-installed

# 4. Start the lab (small resource profile for a 4 GB machine)
make lab-light
```

`make lab-light` downloads a checksum-verified `kind`, runs Helm from a pinned
container image, builds the four images, deploys the charts with reduced
requests and 1 Gi database volumes, then runs the end-to-end checkout smoke test.
The app is on <http://localhost:8080>.

Expect **8-15 minutes** the first time: most of it is Maven dependency downloads
inside the three service image builds. Subsequent runs are much faster.

## What to practise here

| Goal | Command |
| --- | --- |
| See the topology | `kubectl get pods,svc -n bookstore` |
| Break a pod and watch recovery | `kubectl delete pod -n bookstore -l app.kubernetes.io/name=orders-service` |
| Inspect a crash | `kubectl describe pod -n bookstore <pod>` then `kubectl logs` |
| Inspect a database | `kubectl exec -it -n bookstore catalog-service-postgres-0 -- psql -U cataloguser -d catalogdb -c 'TABLE catalog_books;'` |
| Proof of checkout behaviour | `python3 scripts/smoke-test.py --base-url http://localhost:8080` |
| GitOps instead of Helm | `make lab-argocd` (needs `kubectl`, which is already present) |
| Jenkins + SonarQube | `make lab-ci` then `make ci-run` — heavy; see the warning below |

## Limits that matter on Killercoda

- **Resources.** Jenkins plus SonarQube need roughly 3-4 GB on their own. On the
  4 GB playground, run either the application lab **or** the CI lab, not both.
  `make lab-light` exists for this reason.
- **No public URL.** The load balancer/ingress step cannot be practised; use
  `localhost:8080`. TLS and DNS belong to the EKS phase.
- **Image builds are the slow part.** If a session is about to expire, build once
  and keep the session open; a new session must rebuild everything.
- **No AWS.** ECR publishing and EKS provisioning need credentials you should not
  paste into a shared browser VM. Practise those on your own machine.
- If `kind create cluster` fails with a container-runtime error, the VM is not
  providing a usable Docker daemon; run `docker info` and restart the session.

## Clean up before the session ends

```sh
make lab-down      # deletes the kind cluster and its volumes
make ci-reset      # only if you started the CI lab
```

## Where to go next

`docs/eks.md` covers the AWS path: Terraform, ECR push, secrets, EBS storage and
the load balancer.
