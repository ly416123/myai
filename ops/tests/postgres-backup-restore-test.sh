#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd -P)"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
mkdir "$temp_dir/bin" "$temp_dir/backups"
export RESTORE_CAPTURE="$temp_dir/restored.dump"

cat > "$temp_dir/bin/pg_isready" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ " $* " == *" -h $PGHOST "* ]]
[[ " $* " == *" -p $PGPORT "* ]]
[[ " $* " == *" -d $PGDATABASE "* ]]
[[ " $* " == *" -U $PGUSER "* ]]
attempts=0
[[ ! -f "$PGISREADY_ATTEMPTS_FILE" ]] || attempts="$(cat "$PGISREADY_ATTEMPTS_FILE")"
attempts=$((attempts + 1))
printf '%s\n' "$attempts" > "$PGISREADY_ATTEMPTS_FILE"
(( attempts > FAKE_PG_ISREADY_FAILS ))
SH

cat > "$temp_dir/bin/pg_dump" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ "$(stat -f '%Lp' "$PGPASSFILE" 2>/dev/null || stat -c '%a' "$PGPASSFILE")" == "600" ]]
python3 - "$PGPASSFILE" <<'PY'
import pathlib, sys
line = pathlib.Path(sys.argv[1]).read_text()
assert line == r"localhost:55432:source_db:backup_user:pa\:ss\\word" + "\n", "pgpass fields were not escaped safely"
PY
printf 'DEMO-DATABASE-DUMP\nrun-id=sample-42\n'
SH

cat > "$temp_dir/bin/age" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$1" == "--encrypt" ]]; then
  [[ "$2" == "--recipient" && "$3" == "age1testrecipient" && "$4" == "--output" ]]
  python3 -c 'import base64,sys; sys.stdout.buffer.write(base64.b64encode(sys.stdin.buffer.read()))' > "$5"
elif [[ "$1" == "--decrypt" ]]; then
  [[ "$2" == "--identity" && "$3" == "$AGE_IDENTITY_FILE" ]]
  python3 -c 'import base64,sys; sys.stdout.buffer.write(base64.b64decode(sys.stdin.buffer.read()))' < "$4"
else
  exit 2
fi
SH

cat > "$temp_dir/bin/pg_restore" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ " $* " == *" --single-transaction "* ]]
[[ " $* " == *" --dbname=restore_db "* ]]
cat > "$RESTORE_CAPTURE"
grep -q '^DEMO-DATABASE-DUMP$' "$RESTORE_CAPTURE"
SH
chmod +x "$temp_dir/bin/pg_isready" "$temp_dir/bin/pg_dump" "$temp_dir/bin/age" "$temp_dir/bin/pg_restore"
touch "$temp_dir/age.identity"

export PATH="$temp_dir/bin:$PATH"
export PGHOST=localhost PGPORT=55432 PGDATABASE=source_db PGUSER=backup_user
export AGENT_DB_PASSWORD='pa:ss\word' AGE_RECIPIENT=age1testrecipient PGSSLMODE=disable
export PGISREADY_ATTEMPTS_FILE="$temp_dir/pg-isready-attempts"
export FAKE_PG_ISREADY_FAILS=2 AGENT_DB_CONNECT_TIMEOUT_SECONDS=10 AGENT_DB_CONNECT_RETRY_INTERVAL_SECONDS=1
backup="$temp_dir/backups/run-store-test.dump.age"
bash "$repo_root/ops/postgres-backup.sh" "$backup" > "$temp_dir/backup.log"
[[ -s "$backup" && -s "$backup.sha256" ]]
if [[ ! -f "$PGISREADY_ATTEMPTS_FILE" || "$(cat "$PGISREADY_ATTEMPTS_FILE")" != "3" ]]; then
  printf 'Backup did not wait for PostgreSQL to become reachable\n' >&2
  exit 1
fi
! grep -q 'DEMO-DATABASE-DUMP' "$backup"
! grep -q 'pa:ss' "$temp_dir/backup.log"
if bash "$repo_root/ops/postgres-backup.sh" "$backup" >/dev/null 2>&1; then
  printf 'Backup overwrote an existing archive\n' >&2
  exit 1
fi
[[ -s "$backup" && -s "$backup.sha256" ]]

timeout_backup="$temp_dir/backups/timeout.dump.age"
rm -f "$PGISREADY_ATTEMPTS_FILE"
if AGENT_DB_CONNECT_TIMEOUT_SECONDS=2 AGENT_DB_CONNECT_RETRY_INTERVAL_SECONDS=1 FAKE_PG_ISREADY_FAILS=99 \
    bash "$repo_root/ops/postgres-backup.sh" "$timeout_backup" > "$temp_dir/timeout.log" 2>&1; then
  printf 'Backup continued although PostgreSQL never became reachable\n' >&2
  exit 1
fi
[[ ! -e "$timeout_backup" && ! -e "$timeout_backup.sha256" ]]
grep -q 'Timed out waiting for PostgreSQL after 2 seconds' "$temp_dir/timeout.log"

export PGDATABASE=restore_db AGE_IDENTITY_FILE="$temp_dir/age.identity"
export AGENT_RESTORE_CONFIRM_DATABASE=restore_db AGENT_ALLOW_DESTRUCTIVE_RESTORE=YES
bash "$repo_root/ops/postgres-restore.sh" "$backup" > "$temp_dir/restore.log"
grep -q 'run-id=sample-42' "$RESTORE_CAPTURE"

cp "$RESTORE_CAPTURE" "$temp_dir/restore-before-tamper"
printf 'tamper' >> "$backup"
if bash "$repo_root/ops/postgres-restore.sh" "$backup" >/dev/null 2>&1; then
  printf 'Restore accepted an archive with a checksum mismatch\n' >&2
  exit 1
fi
cmp "$temp_dir/restore-before-tamper" "$RESTORE_CAPTURE"

if AGENT_RESTORE_CONFIRM_DATABASE=wrong_db bash "$repo_root/ops/postgres-restore.sh" "$backup" >/dev/null 2>&1; then
  printf 'Restore accepted a mismatched database confirmation\n' >&2
  exit 1
fi

printf 'PostgreSQL backup/restore safety contract passed\n'
