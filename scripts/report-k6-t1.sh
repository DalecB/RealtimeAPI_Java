#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  cat <<'EOF' >&2
Usage:
  bash scripts/report-k6-t1.sh artifacts/k6/t1-fixed-300.json
EOF
  exit 1
fi

jq '{
  file: input_filename,
  baseUrl: .setup_data.baseUrl,
  userCount: (.setup_data.userIds | length),
  benchmarkRateLimitPerSec: .setup_data.benchmarkRateLimitPerSec,
  totalRequests: .metrics.http_reqs.count,
  requestRate: .metrics.http_reqs.rate,
  httpFailedRate: .metrics.http_req_failed.value,
  durationAvgMs: .metrics.http_req_duration.avg,
  durationP90Ms: .metrics.http_req_duration["p(90)"],
  durationP95Ms: .metrics.http_req_duration["p(95)"],
  durationP99Ms: .metrics.http_req_duration["p(99)"],
  durationMaxMs: .metrics.http_req_duration.max,
  waitingP95Ms: .metrics.http_req_waiting["p(95)"],
  successChecks: .metrics.checks.passes,
  failedChecks: .metrics.checks.fails,
  p99ThresholdPassed: .metrics.http_req_duration.thresholds["p(99)<50"],
  errorThresholdPassed: .metrics.http_req_failed.thresholds["rate<0.001"]
}' "$1"
