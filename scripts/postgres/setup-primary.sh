#!/usr/bin/env bash
set -Eeuo pipefail
: "${REPLICATION_PASSWORD:?}" "${READER_PASSWORD:?}" "${PGUSER:?}"
psql --no-psqlrc --set=ON_ERROR_STOP=1 \
  --set=replication_password="$REPLICATION_PASSWORD" \
  --set=reader_password="$READER_PASSWORD" --set=owner="$PGUSER" --set=database="$PGDATABASE" <<'SQL'
SELECT 'CREATE ROLE activity_replicator LOGIN REPLICATION' WHERE NOT EXISTS
 (SELECT 1 FROM pg_roles WHERE rolname='activity_replicator') \gexec
ALTER ROLE activity_replicator WITH LOGIN REPLICATION PASSWORD :'replication_password';
SELECT 'CREATE ROLE activity_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION' WHERE NOT EXISTS
 (SELECT 1 FROM pg_roles WHERE rolname='activity_reader') \gexec
ALTER ROLE activity_reader WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'reader_password';
ALTER ROLE activity_reader SET default_transaction_read_only=on;
GRANT CONNECT ON DATABASE :"database" TO activity_reader;
GRANT USAGE ON SCHEMA public TO activity_reader;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO activity_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT ON TABLES TO activity_reader;
SELECT pg_create_physical_replication_slot('activity_read_replica') WHERE NOT EXISTS
 (SELECT 1 FROM pg_replication_slots WHERE slot_name='activity_read_replica');
SQL
