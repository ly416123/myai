#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

retention_days="${AGENT_BACKUP_RETENTION_DAYS:-}"
if [[ -z "$retention_days" ]]; then
  printf 'Backup retention cleanup is disabled; AGENT_BACKUP_RETENTION_DAYS is not configured\n'
  exit 0
fi
if [[ ! "$retention_days" =~ ^[0-9]{1,5}$ ]] || (( 10#$retention_days < 1 || 10#$retention_days > 36500 )); then
  printf 'AGENT_BACKUP_RETENTION_DAYS must be an integer between 1 and 36500\n' >&2
  exit 2
fi

: "${AGENT_BACKUP_DIR:=/backups}"
if [[ -z "${PGDATABASE:-}" || ! "$PGDATABASE" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  printf 'PGDATABASE is missing or contains characters unsafe for the backup filename\n' >&2
  exit 2
fi
if [[ ! -d "$AGENT_BACKUP_DIR" || ! -w "$AGENT_BACKUP_DIR" ]]; then
  printf 'Backup directory is not writable\n' >&2
  exit 2
fi

minimum_age_days=$((10#$retention_days - 1))
deleted=0
candidates="$(mktemp "${TMPDIR:-/tmp}/myai-backup-retention.XXXXXX")"
trap 'rm -f "$candidates"' EXIT
find "$AGENT_BACKUP_DIR" -maxdepth 1 -type f \
  -name "myai-${PGDATABASE}-*.dump.age" -mtime "+${minimum_age_days}" -print0 > "$candidates"
while IFS= read -r -d '' archive; do
  # Only remove complete, unlocked archives owned by this database. An in-flight
  # archive has a sibling lock directory; a missing checksum requires investigation.
  [[ -f "$archive.sha256" && ! -e "$archive.lock" ]] || continue
  rm -f "$archive" "$archive.sha256"
  deleted=$((deleted + 1))
done < "$candidates"

printf 'Backup retention applied: database=%s retentionDays=%s deleted=%s\n' \
  "$PGDATABASE" "$retention_days" "$deleted"
