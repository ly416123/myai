#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
prometheus_image="${PROMETHEUS_TEST_IMAGE:-prom/prometheus:v3.16.0}"
platform="${PROMETHEUS_TEST_PLATFORM:-linux/amd64}"
mkdir -p "$repo_root/target"
stage_dir="$(mktemp -d "$repo_root/target/prometheus-alert-test.XXXXXX")"
trap 'rm -f "$stage_dir/agent-alerts.yaml" "$stage_dir/agent-alerts-test.yaml"; rmdir "$stage_dir"' EXIT

cp "$repo_root/deploy/k8s/base/agent-alerts.yaml" "$stage_dir/agent-alerts.yaml"
cp "$repo_root/ops/monitoring/agent-alerts-test.yaml" "$stage_dir/agent-alerts-test.yaml"

docker run --rm \
  --platform "$platform" \
  --volume "$stage_dir:/etc/prometheus:ro" \
  --entrypoint=/bin/promtool \
  "$prometheus_image" \
  test rules /etc/prometheus/agent-alerts-test.yaml
