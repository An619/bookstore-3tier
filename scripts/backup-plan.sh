#!/bin/sh
# Show what the opt-in backup CronJob would do, without enabling it.
#
#   sh scripts/backup-plan.sh                 # all three databases
#   sh scripts/backup-plan.sh --chart orders-service
#   sh scripts/backup-plan.sh --render        # also print the rendered CronJob
#
# Answers the two questions people ask before turning backups on: where do the
# archives go, and has a restore ever been proven?
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

chart_filter=""
render=false
for arg in "$@"; do
  case $arg in
    --chart) shift; chart_filter=${1:-} ;;
    --render) render=true ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  esac
  shift || break
done

for chart in catalog-service orders-service inventory-service; do
  [ -n "$chart_filter" ] && [ "$chart" != "$chart_filter" ] && continue
  values_file="helm/$chart/values.yaml"
  schedule=$(sed -n 's/^  schedule: "\(.*\)"/\1/p' "$values_file" | head -n 1)
  bucket=$(sed -n 's/^    bucket: "\(.*\)"/\1/p' "$values_file" | head -n 1)
  prefix=$(sed -n 's/^    prefix: \(.*\)/\1/p' "$values_file" | head -n 1)
  table=$(grep -m1 'SELECT count(\*) FROM' "helm/$chart/templates/backup-cronjob.yaml" \
    | sed -n "s/.*FROM \([a-z_]*\).*/\1/p")
  printf '%s\n' "$chart"
  printf '  enabled:   false (--set backup.enabled=true to turn it on)\n'
  printf '  schedule:  %s\n' "${schedule:-unset}"
  printf '  archive:   /backup/<database>-<timestamp>.sql.gz inside the job pod\n'
  printf '  offsite:   %s\n' "${bucket:-<none: set backup.s3.bucket to copy to S3>}"
  [ -n "$bucket" ] && printf '  s3 prefix: %s\n' "$prefix"
  printf '  verified:  pg_dump -> gunzip -t -> restore into a scratch database -> SELECT count(*) FROM %s\n' "${table:-?}"
  printf '  restore:   follow docs/runbooks/postgres-backup-restore.md\n\n'
  if [ "$render" = true ]; then
    command -v helm >/dev/null 2>&1 || { echo 'helm is required for --render' >&2; exit 1; }
    helm template "$chart" "helm/$chart" --namespace bookstore \
      --set database.createSecret=true --set database.password=lab-only \
      --set backup.enabled=true | sed -n '/kind: CronJob/,/^---/p' | sed 's/^/  /'
    printf '\n'
  fi
done
