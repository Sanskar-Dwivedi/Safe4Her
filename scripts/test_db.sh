#!/usr/bin/env bash
# Runs the Supabase migrations + pgTAP tests against a throwaway local Postgres/PostGIS database.
# Needs: psql, and a Postgres 15+/16 server with postgis + pgtap available.
# Usage: scripts/test_db.sh            (uses $PGHOST/$PGUSER etc., default local socket as postgres)
set -euo pipefail
cd "$(dirname "$0")/.."
DB="${TEST_DB:-s4h_test}"
PSQL=(psql -v ON_ERROR_STOP=1 -q -X)
"${PSQL[@]}" -d postgres -c "drop database if exists $DB" -c "create database $DB"
"${PSQL[@]}" -d "$DB" -f supabase/tests/00_local_supabase_stub.sql
for f in supabase/migrations/*.sql; do
  echo "migrate: $f"
  "${PSQL[@]}" -d "$DB" -f "$f"
done
out=$(psql -X -q -A -t -d "$DB" -f supabase/tests/01_core_test.sql 2>&1) || true
echo "$out"
if echo "$out" | grep -qE "^not ok|ERROR|Looks like"; then
  echo "DB TESTS FAILED"; exit 1
fi
echo "DB TESTS PASSED"
