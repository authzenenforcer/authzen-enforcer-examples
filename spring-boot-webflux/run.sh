#!/bin/sh
# Runs the spring-boot-webflux example with the Keycloak of this repository, which issues the callers'
# tokens and decides, or with Cerbos as the PDP.
#
#   spring-boot-webflux/run.sh          start Keycloak and the application, and show a few calls;
#                                       both keep running
#   spring-boot-webflux/run.sh check    run the decision check of the application, then stop it;
#                                       exits with 1 if a status or a meter differs from the expected one
#   spring-boot-webflux/run.sh stop     stop the application, Keycloak and Cerbos
#
# The secrets of the realm are kept in .env in the repository root, which Git ignores and Docker
# Compose reads by itself. The first run creates it, from DOCUMENT_PORTAL_CLIENT_SECRET and
# EXAMPLE_USER_PASSWORD if they are set, otherwise with new random values. For your own calls,
# load it into your shell with: set -a; . ./.env; set +a
#
# Settings, all optional:
#   KEYCLOAK_PORT  the port of Keycloak, default 8080; kept in .env once given
#   PDP=cerbos     Cerbos as the PDP instead of Keycloak, with the policies in cerbos/
#   CERBOS_PORT    the port of Cerbos, default 3592
#   DEBUG=1        start the application with a debugger port on 127.0.0.1:5005
#   MANAGEMENT_SERVER_PORT
#                  the port of Actuator on 127.0.0.1, default 9082
#
# Requires Docker with Docker Compose, Java 17 or later, Maven, curl and jq.
set -eu

cd "$(dirname "$0")/.."

ENV_FILE=.env
APP_JAR=spring-boot-webflux/target/spring-boot-webflux-1.0-SNAPSHOT.jar
PID_FILE=spring-boot-webflux/target/app.pid
LOG_FILE=app-webflux.log
APP_URL=http://127.0.0.1:8082

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
    PDP=${PDP:-keycloak}
    case "$PDP" in
        keycloak)
            PDP_ARGS=""
            COMPOSE_PROFILE=""
            ;;
        cerbos)
            case "${CERBOS_PORT:-3592}" in
                '' | *[!0-9]*)
                    echo "CERBOS_PORT must be a port number" >&2
                    exit 2
                    ;;
            esac
            # The application profile cerbos; the realm still issues the tokens
            PDP_ARGS="--spring.profiles.active=cerbos --authzen.pdp.url=http://127.0.0.1:${CERBOS_PORT:-3592}"
            COMPOSE_PROFILE="--profile cerbos"
            ;;
        *)
            echo "PDP must be keycloak or cerbos" >&2
            exit 2
            ;;
    esac
}

start_containers() {
    # A Keycloak started with other secrets imported the realm with them: start a fresh one
    running=$(docker compose exec -T keycloak printenv DOCUMENT_PORTAL_CLIENT_SECRET 2>/dev/null || true)
    if [ -n "$running" ] && [ "$running" != "$DOCUMENT_PORTAL_CLIENT_SECRET" ]; then
        echo "Keycloak runs with other secrets than $ENV_FILE; starting a fresh one"
        docker compose --profile cerbos down
    fi
    # shellcheck disable=SC2086
    docker compose $COMPOSE_PROFILE up --wait
}

start_app() {
    stop_app
    mvn -q -f spring-boot-webflux package
    debug=""
    if [ "${DEBUG:-}" = 1 ]; then
        debug="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
    fi
    # shellcheck disable=SC2086
    java $debug -jar "$APP_JAR" --example.realm-url="$REALM" $PDP_ARGS > "$LOG_FILE" 2>&1 &
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
    # Spring Boot reads MANAGEMENT_SERVER_PORT by itself, also from .env; this is for the hints only
    actuator=http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-9082}/actuator
    bob=$(token bob)
    echo
    echo "Calls to the application, $APP_URL, with $PDP as the PDP:"
    call "$bob" GET /documents/handbook "bob reads the handbook"
    call "$bob" DELETE /documents/handbook "bob deletes the handbook (only admins may)"
    call "" GET /documents/handbook "a caller without a token reads the handbook"
    echo
    echo "The application and the containers keep running; the log of the application is in $LOG_FILE."
    echo "For your own calls, load the secrets and get a token, for example for alice:"
    echo
    echo "  set -a; . ./$ENV_FILE; set +a"
    echo "  TOKEN=\$(curl -s $REALM/protocol/openid-connect/token -d grant_type=password \\"
    echo "    -d client_id=documents-cli -d username=alice --data-urlencode \"password=\$EXAMPLE_USER_PASSWORD\" \\"
    echo "    | jq -r .access_token)"
    echo "  curl -i -H \"Authorization: Bearer \$TOKEN\" $APP_URL/documents"
    echo
    echo "Actuator answers on 127.0.0.1 only, on a port of its own:"
    echo
    echo "  curl -s $actuator/health/authzen | jq"
    echo "  curl -s $actuator/metrics/authzen.enforcements | jq"
    echo
    echo "Stop them with: spring-boot-webflux/run.sh stop"
}

case "${1:-start}" in
    start)
        load_env
        start_containers
        start_app
        wait_for_app
        demo
        ;;
    check)
        load_env
        start_containers
        stop_app
        mvn -q -f spring-boot-webflux package
        # shellcheck disable=SC2086
        java -jar "$APP_JAR" --example.realm-url="$REALM" $PDP_ARGS --example.check=true
        ;;
    stop)
        load_env
        stop_app
        docker compose --profile cerbos down
        ;;
    *)
        echo "Usage: $0 [start|check|stop]" >&2
        exit 2
        ;;
esac
