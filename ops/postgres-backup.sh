#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    printf 'Required environment variable is not set: %s\n' "$name" >&2
    exit 2
  fi
}

require_env PGHOST
require_env PGDATABASE
require_env PGUSER
require_env AGENT_DB_PASSWORD
require_env AGE_RECIPIENT

: "${PGPORT:=5432}"
: "${PGSSLMODE:=verify-full}"
export PGPORT PGSSLMODE

if [[ $# -ne 1 || "$1" != *.dump.age ]]; then
  printf 'Usage: %s /secure/path/run-store-YYYYMMDD.dump.age\n' "$0" >&2
  exit 2
fi
if ! command -v pg_dump >/dev/null || ! command -v age >/dev/null || ! command -v sha256sum >/dev/null; then
  printf 'pg_dump, age, and sha256sum must be installed\n' >&2
  exit 2
fi

output="$1"
output_dir="$(dirname "$output")"
output_base="$(basename "$output")"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd -P)"
output="$output_dir/$output_base"
checksum="$output.sha256"
lock="$output.lock"

if ! mkdir "$lock" 2>/dev/null; then
  printf 'Backup destination is locked by another process\n' >&2
  exit 1
fi

if [[ -e "$output" || -e "$checksum" ]]; then
  rmdir "$lock"
  printf 'Backup destination already exists; refusing to overwrite it\n' >&2
  exit 1
fi

passfile=""
encrypted_tmp=""
checksum_tmp=""
created_output=0
published=0
cleanup() {
  [[ -z "$passfile" ]] || rm -f "$passfile"
  [[ -z "$encrypted_tmp" ]] || rm -f "$encrypted_tmp"
  [[ -z "$checksum_tmp" ]] || rm -f "$checksum_tmp"
  rmdir "$lock" 2>/dev/null || true
  if [[ "$created_output" -eq 1 && "$published" -eq 0 ]]; then rm -f "$output"; fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

passfile="$(mktemp "${TMPDIR:-/tmp}/myai-pgpass.XXXXXX")"
encrypted_tmp="$(mktemp "$output_dir/.${output_base}.XXXXXX")"
checksum_tmp="$(mktemp "$output_dir/.${output_base}.sha256.XXXXXX")"
rm -f "$encrypted_tmp"

escape_pgpass() {
  local escaped="${1//\\/\\\\}"
  printf '%s' "${escaped//:/\\:}"
}
{
  escape_pgpass "$PGHOST"; printf ':'
  escape_pgpass "$PGPORT"; printf ':'
  escape_pgpass "$PGDATABASE"; printf ':'
  escape_pgpass "$PGUSER"; printf ':'
  escape_pgpass "$AGENT_DB_PASSWORD"; printf '\n'
} > "$passfile"
chmod 600 "$passfile"
export PGPASSFILE="$passfile"

pg_dump --format=custom --no-owner --no-acl --dbname="$PGDATABASE" \
  | age --encrypt --recipient "$AGE_RECIPIENT" --output "$encrypted_tmp"

digest="$(sha256sum "$encrypted_tmp")"
digest="${digest%% *}"
printf '%s  %s\n' "$digest" "$output_base" > "$checksum_tmp"

mv "$encrypted_tmp" "$output"
created_output=1
mv "$checksum_tmp" "$checksum"
published=1
printf 'Encrypted PostgreSQL backup created: %s\n' "$output"
