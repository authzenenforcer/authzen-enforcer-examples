#!/bin/sh
# Runs the spring-boot-mvc example with the Keycloak of this repository.
#
#   spring-boot-mvc/run.sh          start Keycloak and the application, and show a few calls;
#                                   both keep running
#   spring-boot-mvc/run.sh check    run the decision check of the application, then stop it;
#                                   exits with 1 if a status differs from the expected one
#   spring-boot-mvc/run.sh stop     stop the application and Keycloak
#
# The secrets of the realm are kept in .env in the repository root, which Git ignores and Docker
# Compose reads by itself. The first run creates it, from DOCUMENT_PORTAL_CLIENT_SECRET and
# EXAMPLE_USER_PASSWORD if they are set, otherwise with new random values. For your own calls,
# load it into your shell with: set -a; . ./.env; set +a
#
# Settings, all optional:
#   KEYCLOAK_PORT  the port of Keycloak, default 8080; kept in .env once given
#   DEBUG=1        start the application with a debugger port on 127.0.0.1:5005
#
# Requires Docker with Docker Compose, Java 17 or later, Maven, curl and jq.
set -eu

cd "$(dirname "$0")/.."

ENV_FILE=.env
APP_JAR=spring-boot-mvc/target/spring-boot-mvc-1.0-SNAPSHOT.jar
PID_FILE=spring-boot-mvc/target/app.pid
LOG_FILE=app.log
APP_URL=http://127.0.0.1:8081

load_env() {
    requested_port=${KEYCLOAK_PORT:-}
    if [ ! -f "$ENV_FILE" ]; then
        (
            # Readable by its owner only
            umask 077
            {
                echo "DOCUMENT_PORTAL_CLIENT_SECRET=${DOCUMENT_PORTAL_CLIENT_SECRET:-$(openssl rand -hex 24)}"
                echo "EXAMPLE_USER_PASSWORD=${EXAMPLE_USER_PASSWORD:-$(openssl rand -hex 24)}"
                echo "KEYCLOAK_PORT=${requested_port:-8080}"
            } > "$ENV_FILE"
        )
        echo "Created $ENV_FILE with the secrets of the realm"
    fi
    # The file wins over the shell, so that Keycloak, the application and your calls agree
    set -a
    . "./$ENV_FILE"
    set +a
    if [ -n "$requested_port" ] && [ "$requested_port" != "$KEYCLOAK_PORT" ]; then
        sed -i.bak "s/^KEYCLOAK_PORT=.*/KEYCLOAK_PORT=$requested_port/" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
        KEYCLOAK_PORT=$requested_port
    fi
    KEYCLOAK=http://127.0.0.1:$KEYCLOAK_PORT
    REALM=$KEYCLOAK/realms/authzen-examples
}

start_keycloak() {
    # A Keycloak started with other secrets imported the realm with them: start a fresh one
    running=$(docker compose exec -T keycloak printenv DOCUMENT_PORTAL_CLIENT_SECRET 2>/dev/null || true)
    if [ -n "$running" ] && [ "$running" != "$DOCUMENT_PORTAL_CLIENT_SECRET" ]; then
        echo "Keycloak runs with other secrets than $ENV_FILE; starting a fresh one"
        docker compose down
    fi
    docker compose up --wait
}

start_app() {
    stop_app
    mvn -q -f spring-boot-mvc package
    debug=""
    if [ "${DEBUG:-}" = 1 ]; then
        debug="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
    fi
    # shellcheck disable=SC2086
    java $debug -jar "$APP_JAR" --authzen.pdp.url="$REALM" > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
}

wait_for_app() {
    pid=$(cat "$PID_FILE")
    for _ in $(seq 1 60); do
        if curl -s -o /dev/null "$APP_URL/documents"; then
            return 0
        fi
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    echo "The application did not start; the end of $LOG_FILE:" >&2
    tail -20 "$LOG_FILE" >&2
    exit 1
}

stop_app() {
    if [ -f "$PID_FILE" ]; then
        pid=$(cat "$PID_FILE")
        if kill "$pid" 2>/dev/null; then
            while kill -0 "$pid" 2>/dev/null; do sleep 1; done
        fi
        rm -f "$PID_FILE"
    fi
}

token() {
    curl -fsS "$REALM/protocol/openid-connect/token" \
        -d grant_type=password -d client_id=documents-cli --data-urlencode "username=$1" \
        --data-urlencode "password=$EXAMPLE_USER_PASSWORD" | jq -r .access_token
}

call() {
    if [ -n "$1" ]; then
        status=$(curl -s -o /dev/null -w '%{http_code}' -X "$2" -H "Authorization: Bearer $1" "$APP_URL$3")
    else
        status=$(curl -s -o /dev/null -w '%{http_code}' -X "$2" "$APP_URL$3")
    fi
    printf '  %s %s\n' "$status" "$4"
}

demo() {
    bob=$(token bob)
    echo
    echo "Calls to the application, $APP_URL:"
    call "$bob" GET /documents/handbook "bob reads the handbook"
    call "$bob" DELETE /documents/handbook "bob deletes the handbook (only admins may)"
    call "" GET /documents/handbook "a caller without a token reads the handbook"
    echo
    echo "The application and Keycloak keep running; the log of the application is in $LOG_FILE."
    echo "For your own calls, load the secrets and get a token, for example for alice:"
    echo
    echo "  set -a; . ./$ENV_FILE; set +a"
    echo "  TOKEN=\$(curl -s $REALM/protocol/openid-connect/token -d grant_type=password \\"
    echo "    -d client_id=documents-cli -d username=alice --data-urlencode \"password=\$EXAMPLE_USER_PASSWORD\" \\"
    echo "    | jq -r .access_token)"
    echo "  curl -i -H \"Authorization: Bearer \$TOKEN\" $APP_URL/documents"
    echo
    echo "Stop both with: spring-boot-mvc/run.sh stop"
}

case "${1:-start}" in
    start)
        load_env
        start_keycloak
        start_app
        wait_for_app
        demo
        ;;
    check)
        load_env
        start_keycloak
        stop_app
        mvn -q -f spring-boot-mvc package
        java -jar "$APP_JAR" --authzen.pdp.url="$REALM" --example.check=true
        ;;
    stop)
        load_env
        stop_app
        docker compose down
        ;;
    *)
        echo "Usage: $0 [start|check|stop]" >&2
        exit 2
        ;;
esac
