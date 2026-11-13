#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="v2.20.0"
revision="4ffeda2ef866b0fef372849825a803296483b336"
goarch="${KSM_GOARCH:-amd64}"
image="${KSM_IMAGE:-myai-kube-state-metrics:v2.20.0-my.2}"
go_version="$(go env GOVERSION)"

if [[ "$goarch" != "amd64" && "$goarch" != "arm64" ]]; then
  echo "KSM_GOARCH must be amd64 or arm64" >&2
  exit 2
fi
if [[ "$go_version" != go1.26.10 ]]; then
  echo "kube-state-metrics v2.20.0 builds require Go 1.26.10; found $go_version" >&2
  exit 2
fi

source_dir="$(mktemp -d "${TMPDIR:-/tmp}/myai-ksm-source.XXXXXX")"
build_context="$(mktemp -d "${TMPDIR:-/tmp}/myai-ksm-image.XXXXXX")"
cleanup() {
  rm -rf -- "$source_dir" "$build_context"
}
trap cleanup EXIT

git clone --quiet --depth 1 --branch "$version" --single-branch \
  https://github.com/kubernetes/kube-state-metrics.git "$source_dir"
actual_revision="$(git -C "$source_dir" rev-parse HEAD)"
if [[ "$actual_revision" != "$revision" ]]; then
  echo "Unexpected kube-state-metrics source revision: $actual_revision" >&2
  exit 1
fi

while read -r module version; do
  [[ -z "${module:-}" || "$module" == \#* ]] && continue
  (cd "$source_dir" && go mod edit -require="${module}@${version}")
done < "$repo_root/ops/kube-state-metrics-go-overrides.txt"

client_go_version="$(awk '$1 == "k8s.io/client-go" { print $2; exit }' "$source_dir/go.mod")"
if [[ -z "$client_go_version" ]]; then
  echo "Unable to resolve kube-state-metrics client-go version" >&2
  exit 1
fi
mkdir -p "$build_context/bin"
cp "$source_dir/LICENSE" "$build_context/LICENSE"
(
  cd "$source_dir"
  CGO_ENABLED=0 GOOS=linux GOARCH="$goarch" \
    go build -mod=mod -trimpath \
      -ldflags "-s -w -X github.com/prometheus/common/version.Version=$version -X github.com/prometheus/common/version.Revision=$revision -X github.com/prometheus/common/version.Branch=$version -X k8s.io/kube-state-metrics/v2/pkg/app.ClientGoVersion=$client_go_version" \
      -o "$build_context/bin/kube-state-metrics" .
)

docker build \
  --platform "linux/$goarch" \
  --file "$repo_root/ops/kube-state-metrics.Dockerfile" \
  --build-arg "SOURCE_VERSION=$version" \
  --build-arg "SOURCE_REVISION=$revision" \
  --tag "$image" \
  "$build_context"

printf 'Built %s from kubernetes/kube-state-metrics@%s\n' "$image" "$revision"
