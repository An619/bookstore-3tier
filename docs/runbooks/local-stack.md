# Runbook: local Docker Compose stack

## Start
```bash
docker compose up --build -d
docker compose ps
```

## Verify
```bash
curl -fsS http://localhost:5000/api/health
curl -fsS http://localhost:5000/api/books
curl -fsS http://localhost:8080/
curl -fsS http://localhost:8080/api/books
```

## Diagnose
```bash
docker compose logs --tail=100 database
docker compose logs --tail=100 backend
docker compose logs --tail=100 frontend
docker compose ps
```

- If PostgreSQL is unhealthy, inspect its logs and initialization state.
- If backend health works but books return HTTP 500, inspect backend logs and DB connectivity.
- If the frontend loads but API returns 404, verify the Nginx API proxy was copied into the image, then rebuild frontend.
- If a port is busy, identify the process/container before changing mappings.

## Stop
```bash
docker compose down
```

Destructive local-data removal:
```bash
docker compose down -v
```
Never run destructive cleanup against shared/production resources without an approved backup and change plan.
