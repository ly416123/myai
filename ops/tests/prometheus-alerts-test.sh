#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
prometheus_image="${PROMETHEUS_TEST_IMAGE:-prom/prometheus:v3.16.0}"
platform="${PROMETHEUS_TEST_PLATFORM:-linux/amd64}"
prometheus_version="3.16.0"
prometheus_archive_sha_darwin_amd64="998fb3301c1d77452bdfc481f61353e2e4a3b16c97a6b725c2b7beee5464c8c2"
prometheus_archive_sha_darwin_arm64="f7c71b553316f822bdd860a3ea527ebefb2f8e944e5b7dfd419ae2653cf3ba01"
mkdir -p "$repo_root/target"
stage_dir="$(mktemp -d "$repo_root/target/prometheus-alert-test.XXXXXX")"
tool_dir=""
cleanup() {
  if [[ -n "$tool_dir" ]]; then
    rm -f "$tool_dir/promtool"
    rmdir "$tool_dir"
  fi
  rm -f "$stage_dir/agent-alerts.yaml" "$stage_dir/agent-alerts-test.yaml"
  rmdir "$stage_dir"
}
trap cleanup EXIT

cp "$repo_root/deploy/k8s/base/agent-alerts.yaml" "$stage_dir/agent-alerts.yaml"
cp "$repo_root/ops/monitoring/agent-alerts-test.yaml" "$stage_dir/agent-alerts-test.yaml"
# Prometheus runs as a non-root user; allow it to traverse the temporary directory.
chmod 0755 "$stage_dir"

run_promtool() {
  local executable="$1"
  shift
  (cd "$stage_dir" && "$executable" test rules agent-alerts-test.yaml "$@")
}

use_native_promtool=false
if [[ -z "${PROMETHEUS_TEST_IMAGE:-}" && -z "${PROMETHEUS_TEST_PLATFORM:-}" && "$(uname -s)" == "Darwin" ]]; then
  case "$(uname -m)" in
    arm64|aarch64)
      archive_arch="darwin-arm64"
      archive_sha="$prometheus_archive_sha_darwin_arm64"
      use_native_promtool=true
      ;;
    x86_64|amd64)
      archive_arch="darwin-amd64"
      archive_sha="$prometheus_archive_sha_darwin_amd64"
      use_native_promtool=true
      ;;
  esac
fi

if [[ "$use_native_promtool" == true ]]; then
  cache_dir="${PROMETHEUS_TEST_CACHE_DIR:-$repo_root/target/prometheus-tool-cache}"
  mkdir -p "$cache_dir"
  archive="$cache_dir/prometheus-${prometheus_version}.${archive_arch}.tar.gz"
  tool_dir="$stage_dir/tool"
  mkdir "$tool_dir"
  if [[ ! -f "$archive" ]]; then
    curl --fail --location --silent --show-error \
      --retry 2 --connect-timeout 10 --max-time 180 \
      "https://github.com/prometheus/prometheus/releases/download/v${prometheus_version}/prometheus-${prometheus_version}.${archive_arch}.tar.gz" \
      --output "$archive"
  fi
  printf '%s  %s\n' "$archive_sha" "$archive" | shasum --check --status || {
    echo "Prometheus ${prometheus_version} archive checksum mismatch: $archive" >&2
    exit 1
  }
  tar -xzf "$archive" -C "$tool_dir" --strip-components=1 \
    "prometheus-${prometheus_version}.${archive_arch}/promtool"
  chmod 0755 "$tool_dir/promtool"
  run_promtool "$tool_dir/promtool"
else
  docker run --rm \
    --platform "$platform" \
    --tmpfs /tmp:rw,nosuid,nodev,size=256m \
    --volume "$stage_dir:/etc/prometheus:ro" \
    --entrypoint=/bin/promtool \
    "$prometheus_image" \
    test rules /etc/prometheus/agent-alerts-test.yaml
fi
