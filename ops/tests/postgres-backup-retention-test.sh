#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir "$temp_dir/backups"
export AGENT_BACKUP_DIR="$temp_dir/backups" PGDATABASE=source_db

archive() {
  local name="$1" modified="$2" checksum="${1}.sha256"
  printf 'encrypted' > "$AGENT_BACKUP_DIR/$name"
  touch -t "$modified" "$AGENT_BACKUP_DIR/$name"
  if [[ "$3" == complete ]]; then
    printf 'digest  %s\n' "$name" > "$AGENT_BACKUP_DIR/$checksum"
    touch -t "$modified" "$AGENT_BACKUP_DIR/$checksum"
  fi
}

archive myai-source_db-old.dump.age 202001010000 complete
archive myai-source_db-incomplete.dump.age 202001010000 incomplete
archive myai-source_db-recent.dump.age "$(date '+%Y%m%d%H%M.%S')" complete
archive myai-otherdb-old.dump.age 202001010000 complete
archive myai-source_db-locked.dump.age 202001010000 complete
mkdir "$AGENT_BACKUP_DIR/myai-source_db-locked.dump.age.lock"

# Retention is opt-in; absent policy must never delete stored backups.
bash "$repo_root/ops/prune-postgres-backups.sh" > "$temp_dir/disabled.log"
[[ -f "$AGENT_BACKUP_DIR/myai-source_db-old.dump.age" ]]

if AGENT_BACKUP_RETENTION_DAYS=0 bash "$repo_root/ops/prune-postgres-backups.sh" >/dev/null 2>&1; then
  printf 'Invalid zero-day retention was accepted\n' >&2
  exit 1
fi

AGENT_BACKUP_RETENTION_DAYS=30 bash "$repo_root/ops/prune-postgres-backups.sh" > "$temp_dir/prune.log"
[[ ! -e "$AGENT_BACKUP_DIR/myai-source_db-old.dump.age" ]]
[[ ! -e "$AGENT_BACKUP_DIR/myai-source_db-old.dump.age.sha256" ]]
[[ -f "$AGENT_BACKUP_DIR/myai-source_db-incomplete.dump.age" ]]
[[ -f "$AGENT_BACKUP_DIR/myai-source_db-recent.dump.age" ]]
[[ -f "$AGENT_BACKUP_DIR/myai-otherdb-old.dump.age" ]]
[[ -f "$AGENT_BACKUP_DIR/myai-source_db-locked.dump.age" ]]
[[ -f "$AGENT_BACKUP_DIR/myai-source_db-locked.dump.age.sha256" ]]
grep -q 'deleted=1' "$temp_dir/prune.log"
printf 'PostgreSQL backup retention safety contract passed\n'
