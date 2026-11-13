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
require_env AGE_IDENTITY_FILE
require_env AGENT_RESTORE_CONFIRM_DATABASE
require_env AGENT_ALLOW_DESTRUCTIVE_RESTORE

: "${PGPORT:=5432}"
: "${PGSSLMODE:=verify-full}"
export PGPORT PGSSLMODE

if [[ $# -ne 1 || "$1" != *.dump.age ]]; then
  printf 'Usage: %s /secure/path/run-store-YYYYMMDD.dump.age\n' "$0" >&2
  exit 2
fi
if [[ "$AGENT_ALLOW_DESTRUCTIVE_RESTORE" != "YES" || "$AGENT_RESTORE_CONFIRM_DATABASE" != "$PGDATABASE" ]]; then
  printf 'Restore rejected: set the destructive confirmation and exact target database name\n' >&2
  exit 2
fi
if [[ "$PGDATABASE" == "postgres" || "$PGDATABASE" == template0 || "$PGDATABASE" == template1 ]]; then
  printf 'Restore rejected for a PostgreSQL system database\n' >&2
  exit 2
fi
if [[ ! -r "$AGE_IDENTITY_FILE" || ! -r "$1" || ! -r "$1.sha256" ]]; then
  printf 'Backup, checksum, and age identity files must be readable\n' >&2
  exit 2
fi
if ! command -v pg_restore >/dev/null || ! command -v age >/dev/null || ! command -v sha256sum >/dev/null; then
  printf 'pg_restore, age, and sha256sum must be installed\n' >&2
  exit 2
fi

read -r expected_digest expected_name < "$1.sha256"
if [[ -z "$expected_digest" || "$expected_name" != "$(basename "$1")" ]]; then
  printf 'Backup checksum metadata is invalid\n' >&2
  exit 1
fi
actual_digest="$(sha256sum "$1")"
actual_digest="${actual_digest%% *}"
if [[ "$actual_digest" != "$expected_digest" ]]; then
  printf 'Backup checksum verification failed\n' >&2
  exit 1
fi

passfile="$(mktemp "${TMPDIR:-/tmp}/myai-pgpass.XXXXXX")"
trap 'rm -f "$passfile"' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

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

age --decrypt --identity "$AGE_IDENTITY_FILE" "$1" \
  | pg_restore --format=custom --clean --if-exists --no-owner --no-acl \
      --exit-on-error --single-transaction --dbname="$PGDATABASE"
printf 'PostgreSQL restore completed for database: %s\n' "$PGDATABASE"
