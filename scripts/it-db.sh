#!/usr/bin/env bash
#
# Starts and stops the PostgreSQL used by the integration tests.
#
# Why this exists: Testcontainers' bundled docker-java negotiates Docker API 1.32, and Docker
# Engine 29 refuses anything below 1.44, so on such a host Testcontainers cannot start a container
# even though Docker itself works fine. AbstractPostgresIT accepts -Dit.jdbc.url as an escape
# hatch; this script is the other half of it.
#
# If your Docker is older than 29, you do not need any of this — plain `mvn verify` works.
#
#   scripts/it-db.sh start
#   scripts/it-db.sh stop
#   scripts/it-db.sh psql
#
set -euo pipefail

CONTAINER=ledgersync-it
IMAGE=postgres:16
PORT=55432
DB=ledgersync
USER=ledgersync
PASSWORD=ledgersync

JDBC_URL="jdbc:postgresql://localhost:${PORT}/${DB}"

usage() {
    cat <<USAGE
Usage: $(basename "$0") {start|stop|restart|status|psql|url}

  start    Start ${IMAGE} on port ${PORT} and wait until it accepts connections
  stop     Remove the container (and its data — it is disposable)
  restart  stop then start, giving a clean schema
  status   Show whether the container is up
  psql     Open an interactive shell against the database
  url      Print the JDBC URL to pass as -Dit.jdbc.url

Then run the integration tests with:
  mvn verify -DexcludedGroups=slow -Dit.jdbc.url=${JDBC_URL}
USAGE
}

start() {
    if docker ps --filter "name=^${CONTAINER}$" --format '{{.Names}}' | grep -q "${CONTAINER}"; then
        echo "Already running on port ${PORT}."
        return 0
    fi
    docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
    docker run -d --name "${CONTAINER}" \
        -e POSTGRES_DB="${DB}" \
        -e POSTGRES_USER="${USER}" \
        -e POSTGRES_PASSWORD="${PASSWORD}" \
        -p "${PORT}:5432" \
        "${IMAGE}" >/dev/null

    printf 'Waiting for PostgreSQL'
    for _ in $(seq 1 60); do
        if docker exec "${CONTAINER}" pg_isready -U "${USER}" -d "${DB}" >/dev/null 2>&1; then
            echo " ready."
            echo "Run tests with: mvn verify -DexcludedGroups=slow -Dit.jdbc.url=${JDBC_URL}"
            return 0
        fi
        printf '.'
        sleep 1
    done
    echo
    echo "Timed out waiting for PostgreSQL. Container logs:" >&2
    docker logs --tail 30 "${CONTAINER}" >&2
    return 1
}

stop() {
    docker rm -f "${CONTAINER}" >/dev/null 2>&1 && echo "Stopped." || echo "Not running."
}

case "${1:-}" in
    start)   start ;;
    stop)    stop ;;
    restart) stop; start ;;
    status)  docker ps -a --filter "name=^${CONTAINER}$" \
                 --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' ;;
    psql)    docker exec -it "${CONTAINER}" psql -U "${USER}" -d "${DB}" ;;
    url)     echo "${JDBC_URL}" ;;
    *)       usage; exit 1 ;;
esac
