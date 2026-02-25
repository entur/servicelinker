#!/usr/bin/env bash
# Port-forward k8s OSRM services to localhost for docker-compose.
# Writes OSRM URLs to .env (read automatically by docker compose) and cleans up on exit.
#
# Usage:
#   ./scripts/port-forward-osrm.sh [--context <kubectl-context>]
#   docker compose up   # in another terminal
#
# Check service ports if needed: kubectl get svc -n osrm

set -euo pipefail

NS=osrm
SVC_PORT=80

declare -A FORWARDS=(
  [osrm-bus]=5001
  [osrm-rail]=5002
  [osrm-water]=5003
  [osrm-car]=5004
)

ENV=${1:-dev}
KUBECTL=(kubectl --context "${ENV}")

ENV_FILE="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/.env"

write_env() {
  cat > "$ENV_FILE" <<EOF
OSRM_URLS_BUS=http://host.docker.internal:5001
OSRM_URLS_RAIL=http://host.docker.internal:5002
OSRM_URLS_FERRY=http://host.docker.internal:5003
OSRM_URLS_WATER=http://host.docker.internal:5003
OSRM_URLS_DEFAULT=http://host.docker.internal:5004
EOF
  echo "Wrote OSRM URLs to $ENV_FILE"
}

pids=()

cleanup() {
  echo
  echo "Stopping port-forwards..."
  for pid in "${pids[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  rm -f "$ENV_FILE"
  echo "Removed $ENV_FILE"
}
trap cleanup EXIT INT TERM

write_env

for svc in "${!FORWARDS[@]}"; do
  local_port="${FORWARDS[$svc]}"
  echo "Forwarding localhost:$local_port → $svc:$SVC_PORT"
  "${KUBECTL[@]}" port-forward -n "$NS" "svc/$svc" "$local_port:$SVC_PORT" &
  pids+=($!)
done

echo
echo "Port-forwards active. Run 'docker compose up' in another terminal. Press Ctrl+C to stop."
wait
