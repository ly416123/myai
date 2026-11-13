#!/usr/bin/env bash
set -Eeuo pipefail

age_version="1.3.2"
go_version="1.26.11"
target_architecture="${1:-$(uname -m)}"
case "$target_architecture" in
  x86_64|amd64) target_architecture="amd64" ;;
  aarch64|arm64) target_architecture="arm64" ;;
  *) printf 'Unsupported Linux target architecture: %s\n' "$target_architecture" >&2; exit 2 ;;
esac

host_os="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$host_os" in
  darwin) host_os="darwin" ;;
  linux) host_os="linux" ;;
  *) printf 'Unsupported build host OS: %s\n' "$host_os" >&2; exit 2 ;;
esac
host_architecture="$(uname -m)"
case "$host_architecture" in
  x86_64|amd64) host_architecture="amd64" ;;
  aarch64|arm64) host_architecture="arm64" ;;
  *) printf 'Unsupported build host architecture: %s\n' "$host_architecture" >&2; exit 2 ;;
esac

go_archive="go${go_version}.${host_os}-${host_architecture}.tar.gz"
go_toolchain_base_url="${GO_TOOLCHAIN_BASE_URL:-https://go.dev/dl}"
case "$go_archive" in
  go1.26.11.darwin-arm64.tar.gz) go_checksum="e3a42e67a7a8acf450ebada2b074c7ca863000ca514907b7185b45e8f5cdff57" ;;
  go1.26.11.darwin-amd64.tar.gz) go_checksum="394baae971332f2bc783d6e2004ae57101479bd3b06ed8209a3fe3570d4ce498" ;;
  go1.26.11.linux-arm64.tar.gz) go_checksum="6c2f29b458b5add0835f18b776f69c8881a6d88428f1484a6da55755e620757e" ;;
  go1.26.11.linux-amd64.tar.gz) go_checksum="9849f4e04ee35303714e34fbc3ac8ab44ea3e448a95aa182f91e3b9b79c710ec" ;;
  *) printf 'Unsupported Go toolchain archive: %s\n' "$go_archive" >&2; exit 2 ;;
esac

output_dir="$(cd "$(dirname "$0")" && pwd -P)/.build"
temporary_dir="$(mktemp -d)"
cleanup() {
  chmod -R u+w "$temporary_dir" 2>/dev/null || true
  rm -rf "$temporary_dir"
}
trap cleanup EXIT
mkdir -p "$output_dir" "$temporary_dir/gopath" "$temporary_dir/cache" "$temporary_dir/mod"
curl --fail --location --retry 3 --silent --show-error \
  "${go_toolchain_base_url%/}/$go_archive" --output "$temporary_dir/$go_archive"
if [[ "$host_os" == "darwin" ]]; then
  printf '%s  %s\n' "$go_checksum" "$temporary_dir/$go_archive" | shasum -a 256 --check --status
else
  printf '%s  %s\n' "$go_checksum" "$temporary_dir/$go_archive" | sha256sum --check --status
fi
tar -xzf "$temporary_dir/$go_archive" -C "$temporary_dir"

export GOTOOLCHAIN=local GOOS=linux GOARCH="$target_architecture" CGO_ENABLED=0
export GOPROXY="${GOPROXY:-https://proxy.golang.org,direct}"
export GOCACHE="$temporary_dir/cache" GOMODCACHE="$temporary_dir/mod" GOPATH="$temporary_dir/gopath"
go="${temporary_dir}/go/bin/go"
"$go" mod download "filippo.io/age@v${age_version}"
age_source="$GOMODCACHE/filippo.io/age@v${age_version}"
(cd "$age_source" && "$go" build -trimpath -ldflags="-s -w -X main.Version=v${age_version}" \
  -o "$temporary_dir/bin/age" ./cmd/age)
(cd "$age_source" && "$go" build -trimpath -ldflags="-s -w -X main.Version=v${age_version}" \
  -o "$temporary_dir/bin/age-keygen" ./cmd/age-keygen)
install -m 0555 "$temporary_dir/bin/age" "$output_dir/age"
install -m 0555 "$temporary_dir/bin/age-keygen" "$output_dir/age-keygen"
printf 'Built age %s for linux/%s with checksum-verified Go %s\n' \
  "$age_version" "$target_architecture" "$go_version"
