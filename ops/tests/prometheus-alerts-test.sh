#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
prometheus_image="${PROMETHEUS_TEST_IMAGE:-prom/prometheus:v3.16.0}"
platform="${PROMETHEUS_TEST_PLATFORM:-linux/amd64}"

docker run --rm \
  --platform "$platform" \
  --volume "$repo_root/ops/monitoring:/etc/prometheus:ro" \
  --entrypoint=/bin/promtool \
  "$prometheus_image" \
  test rules /etc/prometheus/agent-alerts-test.yaml
