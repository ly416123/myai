#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
for tool in docker age age-keygen; do
  command -v "$tool" >/dev/null || { printf 'Missing smoke-test dependency: %s\n' "$tool" >&2; exit 2; }
done
backup_image="${MYAI_BACKUP_IMAGE:-myai-postgres-backup:dev}"
docker image inspect "$backup_image" >/dev/null 2>&1 || {
  printf 'Backup image is not available: %s\n' "$backup_image" >&2
  exit 2
}

temp_dir="$(mktemp -d)"
container="myai-pg-restore-smoke-$$"
backup_container="myai-pg-backup-smoke-$$"
network="myai-pg-restore-smoke-net-$$"
backup_volume="myai-pg-backup-smoke-$$"
test_password='temporary-backup-smoke-only'
cleanup() {
  docker rm --force "$container" >/dev/null 2>&1 || true
  docker rm --force "$backup_container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  docker volume rm "$backup_volume" >/dev/null 2>&1 || true
  rm -rf "$temp_dir"
}
trap cleanup EXIT

age-keygen -o "$temp_dir/backup-identity" >/dev/null 2>&1
chmod 600 "$temp_dir/backup-identity"
recipient="$(age-keygen -y "$temp_dir/backup-identity")"
docker network create "$network" >/dev/null

docker run --detach --rm --name "$container" --network "$network" --network-alias postgres \
  --env POSTGRES_DB=source_db --env POSTGRES_USER=backup_user \
  --env POSTGRES_PASSWORD="$test_password" postgres:16-alpine >/dev/null

export SMOKE_POSTGRES_CONTAINER="$container"
export PGHOST=localhost PGPORT=5432 PGUSER=backup_user PGSSLMODE=disable
export AGENT_DB_PASSWORD="$test_password"
mkdir "$temp_dir/bin"
cat > "$temp_dir/bin/pg_restore" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
exec docker exec --interactive --env "PGUSER=${PGUSER:?}" --env "PGPASSWORD=${AGENT_DB_PASSWORD:?}" \
  "${SMOKE_POSTGRES_CONTAINER:?}" pg_restore "$@"
SH
chmod 0755 "$temp_dir/bin/pg_restore"
export PATH="$temp_dir/bin:$PATH"

db_exec() {
  docker exec --interactive --env "PGPASSWORD=$test_password" "$container" "$@"
}
for attempt in {1..60}; do
  if docker exec "$container" pg_isready --username="$PGUSER" >/dev/null 2>&1; then break; fi
  if [[ "$attempt" -eq 60 ]]; then printf 'Temporary PostgreSQL did not become ready\n' >&2; exit 1; fi
  sleep 1
done

db_exec psql --username="$PGUSER" --dbname=source_db --set=ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE backup_probe (run_id text PRIMARY KEY, answer text NOT NULL);
INSERT INTO backup_probe VALUES ('restore-run-42', 'restored-from-encrypted-backup');
SQL

backup_dir="$temp_dir/backups"
mkdir "$backup_dir"
docker volume create "$backup_volume" >/dev/null
docker run --rm --volume "$backup_volume:/backups" --entrypoint sh postgres:16-alpine \
  -ec 'chown 10001:10001 /backups && chmod 0750 /backups && printf expired >/backups/myai-source_db-old.dump.age && printf "digest  myai-source_db-old.dump.age\n" >/backups/myai-source_db-old.dump.age.sha256 && touch -t 202001010000 /backups/myai-source_db-old.dump.age /backups/myai-source_db-old.dump.age.sha256'
docker run --name "$backup_container" --read-only --network "$network" --user 10001:10001 \
  --tmpfs /tmp:rw,noexec,nosuid,size=16m --volume "$backup_volume:/backups" \
  --env PGHOST=postgres --env PGPORT=5432 --env PGDATABASE=source_db --env PGUSER=backup_user \
  --env PGSSLMODE=disable --env AGENT_DB_PASSWORD="$test_password" --env AGE_RECIPIENT="$recipient" \
  --env AGENT_BACKUP_DIR=/backups --env AGENT_BACKUP_RETENTION_DAYS=30 "$backup_image"
docker cp "$backup_container:/backups/." "$backup_dir/"
if [[ -e "$backup_dir/myai-source_db-old.dump.age" || -e "$backup_dir/myai-source_db-old.dump.age.sha256" ]]; then
  printf 'Backup container did not remove the complete expired archive\n' >&2
  exit 1
fi
docker rm "$backup_container" >/dev/null
archive="$(find "$backup_dir" -maxdepth 1 -type f -name '*.dump.age' -print -quit)"
if [[ -z "$archive" || ! -f "$archive.sha256" ]]; then
  printf 'Backup image did not produce an archive and checksum\n' >&2
  exit 1
fi
[[ -n "$archive" && -f "$archive.sha256" ]]
if grep -q 'restored-from-encrypted-backup' "$archive"; then
  printf 'Encrypted archive contains plaintext probe data\n' >&2
  exit 1
fi

db_exec createdb --username="$PGUSER" restore_db
export AGE_IDENTITY_FILE="$temp_dir/backup-identity"
export AGENT_ALLOW_DESTRUCTIVE_RESTORE=YES AGENT_RESTORE_CONFIRM_DATABASE=restore_db
PGDATABASE=restore_db bash "$repo_root/ops/postgres-restore.sh" "$archive"
actual="$(db_exec psql --username="$PGUSER" --dbname=restore_db --tuples-only --no-align \
  --command="SELECT answer FROM backup_probe WHERE run_id = 'restore-run-42'")"
[[ "$actual" == 'restored-from-encrypted-backup' ]]
printf 'Encrypted PostgreSQL backup restored into a separate database\n'
