#!/usr/bin/env bash
set -Eeuo pipefail

: "${AGENT_BACKUP_DIR:=/backups}"
if [[ -z "${PGDATABASE:-}" || ! "$PGDATABASE" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  printf 'PGDATABASE is missing or contains characters unsafe for the backup filename\n' >&2
  exit 2
fi
if [[ ! -d "$AGENT_BACKUP_DIR" || ! -w "$AGENT_BACKUP_DIR" ]]; then
  printf 'Backup directory is not writable\n' >&2
  exit 2
fi

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
archive="$AGENT_BACKUP_DIR/myai-${PGDATABASE}-${timestamp}.dump.age"
/opt/myai/postgres-backup.sh "$archive"
exec /opt/myai/prune-postgres-backups.sh
