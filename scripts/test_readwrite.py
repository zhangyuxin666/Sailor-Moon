"""Disposable real PostgreSQL/Java read-routing acceptance. Never uses the normal project or .env."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        parser.error("Acceptance output must be outside the repository")
    if not (ROOT / "target/activity-assistant-2.0.0.war").is_file():
        parser.error("Build the WAR first with mvn verify")
    project = "sm-rw-test-" + uuid.uuid4().hex[:12]
    image = "sailor-moon-rw-test:" + uuid.uuid4().hex[:12]
    checks = []
    environment = dict(os.environ)
    environment.update(COMPOSE_DISABLE_ENV_FILE="true", COMPOSE_FILE="", COMPOSE_PROFILES="",
                       DB_READ_PASSWORD="offline-reader-password", DB_REPLICATION_PASSWORD="offline-replication-password",
                       POSTGRES_PASSWORD="activity")

    def run(command, *, check=True, timeout=180):
        result = subprocess.run(command, cwd=ROOT, env=environment, text=True, encoding="utf-8",
                                errors="replace", capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stdout[-3000:] + result.stderr[-3000:])
        return result

    run(["docker", "version", "--format", "{{.Server.Version}}"], timeout=15)
    with tempfile.TemporaryDirectory(prefix="sm-readwrite-") as scratch:
        scratch = Path(scratch)
        empty = scratch / "empty.env"
        empty.write_text("", encoding="utf-8")
        config = scratch / "compose.json"
        config.write_text(json.dumps({
            "services": {
                "postgres": {
                    "image": "pgvector/pgvector:pg16",
                    "environment": {"POSTGRES_DB": "activity", "POSTGRES_USER": "activity", "POSTGRES_PASSWORD": "activity"},
                    "volumes": ["postgres_data:/var/lib/postgresql/data"],
                    "healthcheck": {"test": ["CMD-SHELL", "pg_isready -U activity -d activity"], "interval": "2s", "timeout": "3s", "retries": 60}},
                "api": {
                    "image": image, "ports": ["127.0.0.1::8080"],
                    "environment": {
                        "SPRING_DATASOURCE_URL": "jdbc:postgresql://postgres:5432/activity",
                        "SPRING_DATASOURCE_USERNAME": "activity", "SPRING_DATASOURCE_PASSWORD": "activity",
                        "DB_READ_ENABLED": "true", "DB_READ_USERNAME": "activity_reader",
                        "DB_READ_PASSWORD": "offline-reader-password",
                        "DB_READ_URL": "jdbc:postgresql://postgres-replica:5432/activity?connectTimeout=2&socketTimeout=4",
                        "AI_SERVICE_URL": "http://127.0.0.1:1", "QQ_GATEWAY_URL": "http://127.0.0.1:1",
                        "AI_INTERNAL_TOKEN": "offline-ai-token", "QQ_GATEWAY_TOKEN": "offline-qq-token",
                        "QQ_BOT_APP_ID": "", "QQ_BOT_APP_SECRET": "",
                        "CATALINA_OPTS": "-Xms128m -Xmx384m -Dfile.encoding=UTF-8"},
                    "depends_on": {"postgres": {"condition": "service_healthy"}}}},
            "volumes": {"postgres_data": {}}}), encoding="utf-8")
        specification = json.loads(config.read_text(encoding="utf-8"))
        specification["services"]["api2"] = json.loads(json.dumps(specification["services"]["api"]))
        for node in ("api", "api2"):
            specification["services"][node]["environment"]["APP_INSTANCE_NAME"] = node
        config.write_text(json.dumps(specification), encoding="utf-8")
        compose = ["docker", "compose", "--env-file", str(empty), "--project-directory", str(ROOT),
                   "-p", project, "-f", str(config), "-f", str(ROOT / "compose.replication.yml")]

        def dc(*arguments, **kwargs):
            return run(compose + list(arguments), **kwargs)

        def sql(service, statement, *, reader=False, check=True):
            command = ["exec", "-T"]
            if reader:
                command += ["-e", "PGPASSWORD=offline-reader-password"]
            command += [service, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-At", "-U",
                        "activity_reader" if reader else "activity", "-d", "activity"]
            if reader:
                command += ["-h", "127.0.0.1"]
            return dc(*command, "-c", statement, check=check).stdout.strip()

        def wait_for(action, description, seconds=150):
            deadline = time.monotonic() + seconds
            last_error = None
            while time.monotonic() < deadline:
                try:
                    value = action()
                    if value:
                        return value
                except (RuntimeError, OSError, urllib.error.URLError) as error:
                    last_error = error
                time.sleep(1)
            raise AssertionError(f"Timed out: {description}: {last_error}")

        def passed(name):
            checks.append(name)
            print("PASS " + name, flush=True)

        started = built = False
        try:
            # Guard cleanup ownership, even though the generated name is unpredictable.
            for kind in ("container", "volume", "network"):
                command = ["docker", kind, "ls", "-q", "--filter", "label=com.docker.compose.project=" + project]
                if kind == "container":
                    command.append("-a")
                if run(command).stdout.strip():
                    raise RuntimeError("Refusing to reuse an existing project")
            run(["docker", "build", "-f", "scripts/fixtures/Dockerfile.api-runtime", "-t", image, "."], timeout=300)
            built = True
            started = True
            # First create a plain single-database installation and real business data. Then add
            # replication to that SAME volume, matching upgrades of existing student installations.
            run(compose[:-2] + ["up", "-d", "--wait", "--wait-timeout", "180"], timeout=240)
            address = dc("port", "api", "8080").stdout.strip()
            base = "http://" + address
            second_base = "http://" + dc("port", "api2", "8080").stdout.strip()
            jar = http.cookiejar.CookieJar()
            client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

            def request(path, body=None, *, at=None):
                data = None if body is None else json.dumps(body).encode()
                req = urllib.request.Request((at or base) + path, data=data, headers={"Content-Type": "application/json"})
                with client.open(req, timeout=15) as response:
                    return json.load(response)

            wait_for(lambda: request("/health/ready"), "Java application ready")
            wait_for(lambda: request("/health/ready", at=second_base), "second Java application ready")
            request("/auth/bootstrap", {"username": "rw_manager", "display_name": "RW Test",
                    "password": "offline-manager-password", "organization_name": "RW Test", "accept_privacy": True})
            sql("postgres", "INSERT INTO messages(recipient,recipient_account_id,content,created_at) SELECT username,id,'before-lag','2026-01-01' FROM accounts WHERE username='rw_manager'")
            dc("up", "-d", "--wait", "--wait-timeout", "180", timeout=240)
            wait_for(lambda: request("/health/ready"), "primary reconnect after enabling replication")
            assert sql("postgres-replica", "SELECT pg_is_in_recovery()") == "t"
            assert sql("postgres", "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'") == "1"
            passed("existing single-database installation upgraded to physical streaming standby")

            wait_for(lambda: request("/notifications")["notifications"], "notification visible")
            wait_for(lambda: (request("/notifications"), request("/cluster/database"))[1]["replica_queries"] > 0,
                     "business query really routed to standby")
            wait_for(lambda: (request("/notifications", at=second_base), request("/cluster/database", at=second_base))[1]["replica_queries"] > 0,
                     "second node shares the session and routes reads to standby")
            passed("both Tomcat nodes serve committed business data from replica with shared login")

            sql("postgres", "CREATE TABLE rw_future_permissions(id integer); INSERT INTO rw_future_permissions VALUES(1)")
            assert sql("postgres", "SELECT count(*) FROM rw_future_permissions", reader=True) == "1"
            denied = dc("exec", "-T", "-e", "PGPASSWORD=offline-reader-password",
                        "-e", "PGOPTIONS=-c default_transaction_read_only=off", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
                        "-h", "127.0.0.1", "-U", "activity_reader", "-d", "activity", "-c",
                        "DELETE FROM messages WHERE false", check=False)
            assert denied.returncode != 0 and "permission denied" in denied.stderr, denied.stderr
            denied = dc("exec", "-T", "postgres-replica", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "activity", "-d", "activity",
                        "-c", "DELETE FROM messages WHERE false", check=False)
            assert denied.returncode != 0 and "read-only" in denied.stderr, denied.stderr
            passed("reader cannot write even on primary; standby rejects writes")

            sql("postgres-replica", "SELECT pg_wal_replay_pause()")
            # Wait until any in-flight replay has stopped before inserting the new row.
            wait_for(lambda: sql("postgres-replica", "SELECT pg_get_wal_replay_pause_state()") == "paused", "replay paused")
            baseline = request("/cluster/database")["primary_fallbacks"]
            sql("postgres", "INSERT INTO messages(recipient,recipient_account_id,content,created_at) SELECT username,id,'during-lag','2026-01-02' FROM accounts WHERE username='rw_manager'")
            assert any(row["content"] == "during-lag" for row in request("/notifications")["notifications"])
            assert request("/cluster/database")["primary_fallbacks"] > baseline
            passed("paused replay falls back and returns latest committed data")
            sql("postgres-replica", "SELECT pg_wal_replay_resume()")

            baseline = request("/cluster/database")["primary_fallbacks"]
            dc("stop", "postgres-replica")
            assert len(request("/notifications")["notifications"]) == 2
            assert len(request("/notifications", at=second_base)["notifications"]) == 2
            assert request("/cluster/database")["primary_fallbacks"] > baseline
            passed("replica outage preserves business reads via primary")
            before = request("/cluster/database")["replica_queries"]
            dc("start", "postgres-replica")
            wait_for(lambda: (request("/notifications"), request("/cluster/database"))[1]["replica_queries"] > before,
                     "routing resumes after replica restart")
            passed("existing replica volume reconnects and routing resumes")

            # Idempotent setup on an existing database, then primary restart keeps all business data.
            dc("run", "--rm", "--no-deps", "replica-setup")
            dc("restart", "postgres")
            wait_for(lambda: len(request("/notifications")["notifications"]) == 2, "primary reconnect without data loss")
            wait_for(lambda: sql("postgres", "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'") == "1", "replica reconnect")
            passed("repeated setup and primary restart retain data and replication")

            sql("postgres-replica", "SELECT pg_wal_replay_pause()")
            sql("postgres", "DELETE FROM auth_sessions WHERE account_id=(SELECT id FROM accounts WHERE username='rw_manager')")
            for path in ("/notifications", "/cluster/database"):
                try:
                    request(path)
                    raise AssertionError("Revoked session was accepted")
                except urllib.error.HTTPError as error:
                    assert error.code == 403
            passed("session revocation enforced on primary even while replica lags")
            sql("postgres-replica", "SELECT pg_wal_replay_resume()")
        except Exception:
            logs = dc("logs", "--no-color", "--tail", "80", check=False).stdout
            output.parent.mkdir(parents=True, exist_ok=True)
            output.with_suffix(".failure.log").write_text(logs, encoding="utf-8")
            raise
        finally:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps({"project": project, "success": len(checks) == 8, "passed": checks, "count": len(checks)}, indent=2), encoding="utf-8")
            if started:
                dc("down", "-v", "--remove-orphans")
            if built:
                run(["docker", "image", "rm", image], check=False)


if __name__ == "__main__":
    main()
