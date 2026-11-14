#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

python3 - <<'PY'
import re
import subprocess
import sys
import hashlib
import json
from pathlib import Path

expected_repositories = {
    "deploy/k8s/base/l5-postgres.yaml": {"postgres": 3},
    "deploy/k8s/base/l5-foundation.yaml": {
        "otel/opentelemetry-collector-contrib": 1,
        "prom/prometheus": 1,
    },
    "deploy/k8s/base/tempo.yaml": {"grafana/tempo": 1},
    "deploy/k8s/base/grafana.yaml": {"grafana/grafana": 1},
}

failed = False
images = {}
for manifest_path, expected in expected_repositories.items():
    manifest = Path(manifest_path).read_text()
    references = re.findall(r"^\s*image:\s*(\S+)\s*$", manifest, re.MULTILINE)
    actual_counts = {}
    for reference in references:
        if "@sha256:" not in reference:
            print(f"FAIL {manifest_path}: image is not digest-pinned: {reference}")
            failed = True
            continue
        image, digest = reference.rsplit("@sha256:", 1)
        if not re.fullmatch(r"[a-f0-9]{64}", digest):
            print(f"FAIL {manifest_path}: invalid digest for {image}")
            failed = True
            continue
        repository = image.rsplit(":", 1)[0]
        actual_counts[repository] = actual_counts.get(repository, 0) + 1
        images.setdefault(image, set()).add("sha256:" + digest)

    if actual_counts != expected:
        print(f"FAIL {manifest_path}: expected image repositories/counts {expected}, found {actual_counts}")
        failed = True

for image, pinned_digests in images.items():
    if len(pinned_digests) != 1:
        print(f"FAIL {image}: inconsistent digests in deployment manifests: {', '.join(sorted(pinned_digests))}")
        failed = True
        continue

    result = subprocess.run(
        ["docker", "buildx", "imagetools", "inspect", "--raw", image],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"FAIL {image}: registry manifest lookup failed: {result.stderr.strip()}")
        failed = True
        continue

    try:
        manifest = json.loads(result.stdout)
    except json.JSONDecodeError:
        print(f"FAIL {image}: registry returned invalid manifest JSON")
        failed = True
        continue

    descriptors = manifest.get("manifests")
    if isinstance(descriptors, list):
        platform_manifests = [
            descriptor
            for descriptor in descriptors
            if descriptor.get("platform", {}).get("os") == "linux"
            and descriptor.get("platform", {}).get("architecture") == "amd64"
        ]
        if len(platform_manifests) != 1:
            print(f"FAIL {image}: expected exactly one linux/amd64 manifest, found {len(platform_manifests)}")
            failed = True
            continue
        registry_digest = platform_manifests[0].get("digest", "")
    else:
        registry_digest = "sha256:" + hashlib.sha256(result.stdout.encode()).hexdigest()

    if not re.fullmatch(r"sha256:[a-f0-9]{64}", registry_digest):
        print(f"FAIL {image}: registry returned an invalid linux/amd64 manifest digest")
        failed = True
        continue

    pinned_digest = next(iter(pinned_digests))
    if pinned_digest != registry_digest:
        print(f"FAIL {image}: deployment pins {pinned_digest} but linux/amd64 resolves to {registry_digest}")
        failed = True
    else:
        print(f"PASS {image} {registry_digest}")

if failed:
    sys.exit(1)
PY
