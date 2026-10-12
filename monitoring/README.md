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

## What to monitor
- Deployment availability, pod restarts, CPU/memory saturation and pending PVCs.
- API liveness/readiness and HTTP error rate (application-level metrics need to be added separately).
- Database availability, connection usage, storage growth and backup/restore outcomes.
- CI build duration/failure rate and deployment/rollback outcomes.

Define actionable alerts, owners, severity and runbooks. Avoid paging on every transient event; tune thresholds using observed lab behavior.
