#!/usr/bin/env bash
set -Eeuo pipefail
: "${PGDATA:?}" "${REPLICATION_PASSWORD:?}"
if [ "$(id -u)" = 0 ]; then
  mkdir -p "$PGDATA"
  chown postgres:postgres "$PGDATA"
  chmod 700 "$PGDATA"
  exec gosu postgres bash "$0"
fi
umask 077
# Escape libpq password-file separators, without putting a password in postgres command arguments.
escaped=${REPLICATION_PASSWORD//\\/\\\\}
escaped=${escaped//:/\\:}
printf 'postgres:5432:*:activity_replicator:%s\n' "$escaped" > /var/lib/postgresql/.pgpass
if [ ! -f "$PGDATA/PG_VERSION" ]; then
  if [ -n "$(find "$PGDATA" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo 'Replica directory is not empty. Refusing to delete or overwrite it.' >&2
    exit 1
  fi
  pg_basebackup --dbname='host=postgres port=5432 user=activity_replicator passfile=/var/lib/postgresql/.pgpass application_name=activity-read-replica' \
    -D "$PGDATA" -R -X stream --checkpoint=fast --slot=activity_read_replica
elif [ ! -f "$PGDATA/standby.signal" ]; then
  echo 'Existing database is not a standby. Refusing to start or overwrite it.' >&2
  exit 1
fi
exec postgres -D "$PGDATA" -c hot_standby=on
