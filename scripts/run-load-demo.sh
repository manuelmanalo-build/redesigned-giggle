#!/usr/bin/env bash
set -euo pipefail

APP_URL="${APP_URL:-http://localhost:8080}"
TOTAL_ORDERS="${TOTAL_ORDERS:-25}"
CONCURRENCY="${CONCURRENCY:-5}"
SYMBOL="${SYMBOL:-AAPL}"

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to run this demo script" >&2
  exit 1
fi

if ! [[ "$TOTAL_ORDERS" =~ ^[0-9]+$ ]] || [[ "$TOTAL_ORDERS" -lt 1 ]]; then
  echo "TOTAL_ORDERS must be a positive integer" >&2
  exit 1
fi

if ! [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] || [[ "$CONCURRENCY" -lt 1 ]]; then
  echo "CONCURRENCY must be a positive integer" >&2
  exit 1
fi

submit_order() {
  local index="$1"
  local client_order_id="LOAD-DEMO-${index}-$(date +%s%N)"
  local idempotency_key="load-demo-${client_order_id}"
  local correlation_id="load-demo-${index}"

  curl --silent --show-error --fail \
    --request POST "${APP_URL}/api/v1/orders" \
    --header "Content-Type: application/json" \
    --header "Idempotency-Key: ${idempotency_key}" \
    --header "X-Correlation-Id: ${correlation_id}" \
    --data "{
      \"clientOrderId\": \"${client_order_id}\",
      \"accountId\": \"ACC-LOAD\",
      \"symbol\": \"${SYMBOL}\",
      \"side\": \"BUY\",
      \"type\": \"LIMIT\",
      \"quantity\": 100,
      \"limitPrice\": 100.00
    }" >/dev/null
}

echo "Submitting ${TOTAL_ORDERS} demo orders to ${APP_URL} with concurrency ${CONCURRENCY}"
echo "This is a lightweight smoke/load demo, not a benchmark."

active_jobs=0
for index in $(seq 1 "$TOTAL_ORDERS"); do
  submit_order "$index" &
  active_jobs=$((active_jobs + 1))

  if [[ "$active_jobs" -ge "$CONCURRENCY" ]]; then
    wait -n
    active_jobs=$((active_jobs - 1))
  fi
done

wait
echo "Completed ${TOTAL_ORDERS} demo submissions."
