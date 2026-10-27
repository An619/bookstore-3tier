# Checkout backlog and reconciliation

Alerts: `CheckoutOutboxBacklog`, `CheckoutReconciliationStuck`.

Both are derived from two gauges the orders service exports on
`/actuator/prometheus`:

| Metric | Meaning | Healthy |
| --- | --- | --- |
| `checkout_outbox_pending` | outbox events the worker has not finished | 0 |
| `checkout_attempts_needs_reconciliation` | checkouts the reconciler has not settled | 0 |

A `-1` value means the service could not read the database that cycle.

## 1. Is the worker running?

```sh
kubectl -n bookstore logs deploy/orders-service | grep -E "Checkout|Reserved|Released"
kubectl -n bookstore exec deploy/orders-service -- \
  wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep checkout_
```

`CHECKOUT_WORKER_ENABLED=false` (or `checkout.worker.enabled=false`) turns the
worker off, which will grow the backlog. Check the deployment values first.

## 2. Is the inventory service reachable?

```sh
kubectl -n bookstore exec deploy/orders-service -- \
  wget -qO- "http://inventory-service:8083/actuator/health/readiness"
kubectl -n bookstore get networkpolicy
```

If NetworkPolicy is enabled, the orders pod may only reach the inventory pod;
DNS and the inventory service port must both be allowed.

## 3. Read the queue

```sh
kubectl -n bookstore exec -it orders-service-postgres-0 -- \
  psql -U serviceuser -d ordersdb -c \
  "SELECT aggregate_key, event_type, status, attempts, reconcile_cycles, last_error
   FROM checkout_outbox WHERE status <> 'DONE' ORDER BY id;"
```

- `PENDING` with a rising `attempts`: the dependency is failing; fix it and the
  worker drains on its own.
- `FAILED` with `reconcile_cycles` below the limit: the reconciler re-queues it.
- `RELEASE_STOCK` rows retry forever on purpose: releasing is idempotent, so the
  worst case is a delayed compensation, never a double release.

## 4. Do not hand-edit stock

`inventory_operations` is the ledger the reconciler trusts. If you change
`inventory_items.available_quantity` by hand, reconciliation will still follow
the ledger and the numbers will disagree. To force a checkout through again:

```sql
UPDATE checkout_attempts SET status = 'NEEDS_RECONCILIATION'
 WHERE idempotency_key = '<key>';
```

The reconciler re-reads the inventory ledger (and, with payments enabled, the
payment record) and settles it on the next cycle.

## 5. Escalate

If the backlog persists with healthy dependencies, capture the metrics, the
`checkout_outbox` rows and the last 200 log lines, then roll back with
`docs/runbooks/deployment-rollback.md`.
