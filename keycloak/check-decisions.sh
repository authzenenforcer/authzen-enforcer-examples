#!/bin/sh
# Checks the decisions of the authzen-examples realm against the table in the README.
#
#   DOCUMENT_PORTAL_CLIENT_SECRET=... keycloak/check-decisions.sh
#
# Settings, all optional:
#   PDP_URL    the realm, default http://127.0.0.1:${KEYCLOAK_PORT:-8080}/realms/authzen-examples
#   TOKEN_URL  default $PDP_URL/protocol/openid-connect/token
#   CLIENT_ID  default document-portal
#
# Requires curl and jq. Exits with 1 if any decision differs from the expected one.
set -eu

: "${DOCUMENT_PORTAL_CLIENT_SECRET:?set DOCUMENT_PORTAL_CLIENT_SECRET}"
PDP_URL="${PDP_URL:-http://127.0.0.1:${KEYCLOAK_PORT:-8080}/realms/authzen-examples}"
TOKEN_URL="${TOKEN_URL:-$PDP_URL/protocol/openid-connect/token}"
CLIENT_ID="${CLIENT_ID:-document-portal}"

TOKEN=$(curl -fsS "$TOKEN_URL" \
    -d grant_type=client_credentials --data-urlencode "client_id=$CLIENT_ID" \
    --data-urlencode "client_secret=$DOCUMENT_PORTAL_CLIENT_SECRET" | jq -r .access_token)
# The metadata location of the AuthZEN specification: the well-known path goes between the host
# and the path of the PDP URL
origin=$(printf '%s' "$PDP_URL" | sed -E 's#^([a-z]+://[^/]+).*#\1#')
ENDPOINT=$(curl -fsS "$origin/.well-known/authzen-configuration${PDP_URL#"$origin"}" \
    | jq -r .access_evaluation_endpoint)

failures=0
total=0

# check <expected> <subject type> <subject id> <resource type> <resource id> <action>
#       [<resource properties JSON>] [<context JSON>]
check() {
    body=$(jq -cn --arg st "$2" --arg si "$3" --arg rt "$4" --arg ri "$5" --arg an "$6" \
        --argjson rp "${7:-null}" --argjson cx "${8:-null}" '
        {subject: {type: $st, id: $si}, action: {name: $an}, resource: {type: $rt, id: $ri}}
        | if $rp then .resource.properties = $rp else . end
        | if $cx then .context = $cx else . end')
    actual=$(curl -fsS "$ENDPOINT" -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d "$body" | jq -r .decision)
    total=$((total + 1))
    if [ "$actual" = "$1" ]; then
        result=ok
    else
        result="FAILED (got $actual)"
        failures=$((failures + 1))
    fi
    printf '%-6s %-15s %-25s %-7s %-22s %-14s %s\n' "$1" "$3" "$4:$5" "$6" "${7:-}" "${8:-}" "$result"
}

printf '%-6s %-15s %-25s %-7s %-22s %-14s %s\n' expect subject resource action properties context result

# Every role reads the handbook and the release notes; erin has no role.
for user in alice bob carol dave; do
    check true user "$user" document handbook read
    check true user "$user" document release-notes read
done
check false user erin document handbook read

# Editors and admins update the handbook; only admins delete.
check true  user bob   document handbook update
check false user carol document handbook update
check false user bob   document handbook delete
check true  user alice document handbook delete

# The release notes can be updated while they are a draft (resource.properties).
check true  user bob   document release-notes update '{"status":"draft"}'
check false user bob   document release-notes update '{"status":"published"}'
check false user bob   document release-notes update
check false user carol document release-notes update '{"status":"draft"}'

# HR reads and updates the salary report after multi-factor authentication (context).
check true  user dave  document salary-report read   '' '{"mfa":true}'
check false user dave  document salary-report read
check false user dave  document salary-report read   '' '{"mfa":false}'
check true  user dave  document salary-report update '' '{"mfa":true}'
check false user alice document salary-report read   '' '{"mfa":true}'
check true  user alice document salary-report delete

# The document collection.
check true  user carol collection document list
check false user carol collection document create
check true  user bob   collection document create
check false user erin  collection document list

# The PEP itself as the subject: its service account has the viewer role.
check true  client "$CLIENT_ID" document handbook read
check false client "$CLIENT_ID" document handbook update

# Unknown subjects, resources and actions are denied, and so is a wrong resource type.
check false user mallory document handbook read
check false user alice   document missing   read
check false user alice   document handbook  approve
check false user alice   collection handbook read

echo "$((total - failures))/$total decisions as expected"
[ "$failures" -eq 0 ]
