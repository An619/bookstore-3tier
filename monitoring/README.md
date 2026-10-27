# Monitoring with Prometheus and Grafana

This monitoring setup is a later Kubernetes milestone. It is not installed automatically by the application Helm chart.

## Install kube-prometheus-stack

Install it only in a cluster where you intend to pay for and operate the monitoring resources. Review the current chart version and values first.

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace
```

Verify pods and services in the monitoring namespace. Access Grafana using port-forward for a lab rather than exposing it publicly:

```bash
kubectl get pods,svc -n monitoring
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80
```

The Grafana service name may vary if you change the release name or chart values. Use kubectl get svc -n monitoring to confirm it. Retrieve the generated Grafana admin password from the Kubernetes Secret instead of committing it anywhere.

## Alert rules

bookstore-alerts.yaml contains example PrometheusRule alerts for unavailable deployments, CrashLoopBackOff and pending persistent volume claims. It requires the Prometheus Operator CRDs and the matching Prometheus rule selector. By default, the rule label assumes the Helm release is named kube-prometheus-stack; change the label if you choose another release name.

## Application metrics

Each service exposes Micrometer/Prometheus metrics at `/actuator/prometheus`
(only `health`, `info` and `prometheus` actuator endpoints are exposed). For
example, through a local forward:

```sh
kubectl -n bookstore port-forward svc/catalog-service 8081:8081
curl -f http://localhost:8081/actuator/prometheus | head
```

Each application chart has an **opt-in** ServiceMonitor
(`serviceMonitor.enabled`, default `false`) that requires the Prometheus
Operator CRDs. Its `serviceMonitor.labels` must match the Prometheus resource's
`serviceMonitorSelector` (defaults to `release: kube-prometheus-stack`):

```sh
helm upgrade --install catalog-service helm/catalog-service -n bookstore \
  --set serviceMonitor.enabled=true
```

Repeat for the other two services. The frontend only serves static files and has
no application metrics. If you enable NetworkPolicy, also set
`networkPolicy.metricsNamespace=monitoring` so Prometheus can scrape; without
that value the API NetworkPolicies block scraping.

Metrics are not persisted here: retention, long-term storage, dashboards and
alert routing must be configured in your monitoring stack.

## What to monitor
- Deployment availability, pod restarts, CPU/memory saturation and pending PVCs.
- API liveness/readiness, HTTP error rate and JVM/HTTP metrics from `/actuator/prometheus`.
- Database availability, connection usage, storage growth and backup/restore outcomes.
- CI build duration/failure rate and deployment/rollback outcomes.

Define actionable alerts, owners, severity and runbooks. Avoid paging on every transient event; tune thresholds using observed lab behavior.
