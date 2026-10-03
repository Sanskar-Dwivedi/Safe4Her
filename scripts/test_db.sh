#!/usr/bin/env bash
# Runs the Supabase migrations + pgTAP tests against a throwaway local Postgres/PostGIS database.
# Needs: psql, and a Postgres 15+/16 server with postgis + pgtap available.
# Usage: scripts/test_db.sh            (uses $PGHOST/$PGUSER etc., default local socket as postgres)
set -euo pipefail
cd "$(dirname "$0")/.."
DB="${TEST_DB:-s4h_test}"
[[ "$DB" =~ ^s4h_test[a-z0-9_]*$ ]] || { echo 'TEST_DB must start with s4h_test and contain only lowercase letters, numbers and underscores'; exit 1; }
PSQL=(psql -v ON_ERROR_STOP=1 -q -X)
"${PSQL[@]}" -d postgres -c "drop database if exists $DB" -c "create database $DB"
"${PSQL[@]}" -d "$DB" -f supabase/tests/00_local_supabase_stub.sql
for f in supabase/migrations/*.sql; do
  echo "migrate: $f"
  "${PSQL[@]}" -d "$DB" -f "$f"
done
for f in supabase/tests/*_test.sql; do
  out=$(psql -X -v ON_ERROR_STOP=1 -q -A -t -d "$DB" -f "$f" 2>&1) || { echo "$out"; exit 1; }
  echo "$out"
  if echo "$out" | grep -qE "^not ok|ERROR|Looks like"; then
    echo "DB TESTS FAILED: $f"; exit 1
  fi
done
echo "DB TESTS PASSED"
