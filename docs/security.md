# Security posture

What the repository enforces, what it scans, and what is still open. Update this
page whenever a finding is accepted.

## What is enforced

- **No committed secrets.** Passwords are generated into `.tools/` (git-ignored)
  at bring-up time; the only credentials in the repository are labelled "LAB
  ONLY" Compose defaults.
- **Least privilege in images.** Every service image runs as a non-root user
  (10001 / 101), drops all capabilities, disables privilege escalation and sets
  `seccompProfile: RuntimeDefault`. No pod mounts a service-account token unless
  it needs one.
- **Optional authorization.** Orders and inventory can require JWTs with
  `orders.read|write` / `inventory.read|write`; enabling enforcement without an
  issuer or JWKS URI fails at startup instead of silently allowing traffic.
  Probes and `/actuator/prometheus` stay public.
- **Supply chain.** `.github/workflows/security.yml` scans every image with a
  pinned Trivy, publishes CycloneDX SBOMs, and fails on CRITICAL findings that
  have a fix. Exceptions live in `.trivyignore` and must carry a reason, an
  owner and an expiry date; a test fails if one does not.

## Running the scan

```sh
gh workflow run security.yml -f fail-on=CRITICAL     # on demand
gh run list --workflow=security.yml                  # results
gh run download <run-id> -n security-reports        # reports/*.txt + sbom/*.json
```

A local equivalent for a single image:

```sh
docker build -t bookstore/orders-service:scan -f services/orders-service/Dockerfile .
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy:0.57.1 image --ignore-unfixed --severity CRITICAL,HIGH \
  bookstore/orders-service:scan
```

## Known findings

| Finding | State | Next action |
| --- | --- | --- |
| `CVE-2026-47884` spring-webmvc (all three services) | **Accepted**, expires 2026-12-31 | Fixed only in Spring Framework 7.0.9 (Boot 4). Requires the Spring Boot 4 upgrade before production; see the entry in `.trivyignore`. |
| `CVE-2026-65182` tomcat-embed-core (all three services) | **Accepted**, expires 2026-12-15 | The advisory names 10.1.58, which is not published in Maven Central yet. Bump `tomcat.version` and delete the ignore line once it resolves. |
| CRITICAL findings in the frontend image | **Open** | The gate reports them; download `security-reports` for the package list and bump the `nginxinc/nginx-unprivileged` tag (or rebuild the base). Do not add them to `.trivyignore` without a written reason. |

Fixed in this branch after the first scan run: `CVE-2025-41232` and
`CVE-2026-22732` (Spring Security), `CVE-2026-41293` (Tomcat 10.1.40 -> 10.1.55),
plus the Alpine package CVEs that `apk upgrade` cleared in all four images.

## What is not covered

- No runtime protection (Falco, AppArmor/SELinux profiles beyond the defaults).
- No image signing or admission control (cosign/Sigstore, Kyverno) - SBOMs are
  produced, but nothing verifies provenance at deploy time.
- No secret rotation: the lab Secrets are static, and the External Secrets path
  is opt-in and unversioned by design.
- No penetration test, and the Keycloak realm is a lab aid, not a production IdP.
