# spring-boot-mvc

authzen-enforcer in a Spring Boot 4 application with Spring MVC and Spring Security. The starter
`authzen-enforcer-spring-boot-starter` asks the PDP before every handler annotated with
`@CheckPermission` and answers with `401` or `403` when it may not run.

Spring Boot offers two web stacks: Spring MVC on servlets, used here, and the reactive Spring
WebFlux. The starter supports Spring MVC for now; a WebFlux example follows once it supports that
too.

> **Not released yet.** The starter is in development and not on Maven Central. Until it is, build
> it from the main repository first (`mvn install -DskipTests` in a clone of
> [authzen-enforcer](https://github.com/authzenenforcer/authzen-enforcer)); this example uses
> `0.2.0-SNAPSHOT`.

## What it shows

- **Zero configuration for the PDP.** `authzen.pdp.url` is the only setting the starter needs; it
  finds the endpoints in the PDP's metadata.
- **Inference.** [`DocumentController`](src/main/java/io/github/authzenenforcer/examples/springbootmvc/DocumentController.java)
  names no action and no resource. `GET /documents/handbook` becomes `read` on
  `{"type": "document", "id": "handbook"}`, `GET /documents` becomes `list` on
  `{"type": "collection", "id": "document"}`, and the subject is the caller of the access token.
- **Explicit values win over inference.** `GET /documents/{id}/download` would be inferred as listing
  a nested collection; `@CheckPermission(action = "read", resourceType = "document", resourceId = "#id")`
  on that handler makes it a `read` of the document instead.
- **Context from claims.** Reading a document sends `context.mfa`, derived from the `acr` claim
  with an expression. Expressions can read path variables, a few values of the request and the
  caller's claims, never the caller's token.
- **The PEP's own identity.** The application authenticates to the PDP as the confidential client
  `document-portal`, with the client credentials grant. It never forwards the caller's token.
- **Answers.** No token: `401` with Spring Security's challenge. A deny or a PDP failure: `403`.

## Running it

Run these steps **in one shell, in the repository root** (not in `spring-boot-mvc/`). They start a fresh
Keycloak with new secrets, so the realm and the application always use the same values. If port
`8080` is taken on your machine, uncomment the `KEYCLOAK_PORT` line.

```bash
# 1. Secrets for the realm; the application reads the same variables
export DOCUMENT_PORTAL_CLIENT_SECRET=$(openssl rand -hex 24)
export EXAMPLE_USER_PASSWORD=$(openssl rand -hex 24)
# export KEYCLOAK_PORT=8180
KEYCLOAK=http://127.0.0.1:${KEYCLOAK_PORT:-8080}

# 2. A fresh Keycloak, which imports the realm with these secrets
docker compose down
docker compose up --wait

# 3. Build and start the application on port 8081, in the background, logging to app.log
mvn -q -f spring-boot-mvc package
java -jar spring-boot-mvc/target/spring-boot-mvc-1.0-SNAPSHOT.jar \
  --authzen.pdp.url=$KEYCLOAK/realms/authzen-examples > app.log 2>&1 &
until curl -s -o /dev/null http://127.0.0.1:8081/documents
do
  sleep 1
done

# 4. A token for bob from Keycloak, and calls to the application with it
TOKEN=$(curl -s $KEYCLOAK/realms/authzen-examples/protocol/openid-connect/token \
  -d grant_type=password -d client_id=documents-cli -d username=bob \
  --data-urlencode "password=$EXAMPLE_USER_PASSWORD" | jq -r .access_token)

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8081/documents/handbook             # 200
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8081/documents/handbook/download    # 200
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8081/documents/handbook   # 403
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/documents/handbook                                                # 401

# 5. Stop the application and Keycloak
kill %1
docker compose down
```

Keycloak issues the tokens and makes the decisions (`8080`, or `KEYCLOAK_PORT`); the application
serves the documents API on `8081`. The other users are `alice` (admin), `carol` (viewer), `dave`
(hr) and `erin` (no role); see the [main README](../README.md#the-scenario).

`documents-cli` is a public client with the password grant, which the realm has for this example
only. A real client signs users in with the authorization code flow.

### The decision check

With `--example.check=true`, the application calls its own endpoints as the users of the realm once
it has started, prints every status and exits with status 1 if one differs from the expected one.
After steps 1 and 2 above, in the same shell:

```bash
java -jar spring-boot-mvc/target/spring-boot-mvc-1.0-SNAPSHOT.jar \
  --authzen.pdp.url=$KEYCLOAK/realms/authzen-examples --example.check=true
```

```text
A document
  200 bob reads the handbook
  403 erin, who has no role, reads the handbook
  401 an anonymous caller reads the handbook
  200 bob downloads the handbook (explicit values)
  403 erin downloads the handbook (explicit values)
  200 bob updates the handbook
  403 bob deletes the handbook
  403 dave (hr) reads the salary report without MFA

The document collection
  200 carol lists the documents
  403 carol creates a document
  201 bob creates a document
  204 alice deletes the handbook

All statuses are as expected.
```

## Configuration

[`application.yaml`](src/main/resources/application.yaml) has defaults that fit the Keycloak of this
repository. Override them on the command line, such as
`--authzen.pdp.url=http://127.0.0.1:8180/realms/authzen-examples`, or with environment variables,
such as `AUTHZEN_PDP_URL`.

| Setting | Default | |
|---|---|---|
| `authzen.pdp.url` | `http://127.0.0.1:8080/realms/authzen-examples` | The PDP; also the issuer of the callers' tokens |
| `authzen.enforcer.subject.id-claim` | `preferred_username` | The realm's policies name users by username |
| `authzen.enforcer.resource.singularize` | `true` | `/documents/{id}` becomes the type `document` |
| `server.port` | `8081` | Keycloak uses `8080` |
| `example.client-id` | `document-portal` | The PEP's client; its secret comes from `DOCUMENT_PORTAL_CLIENT_SECRET` |
| `example.user-client-id` | `documents-cli` | The client the decision check gets user tokens from; the password comes from `EXAMPLE_USER_PASSWORD` |

## Not shown

- `resource.properties`: the starter infers the type and id of a resource, not its attributes, so
  the realm's rule for updating the release notes (`status` must be `draft`) always denies here.
- The client subject (`{"type": "client", "id": "document-portal"}`): the subject is always the
  caller of the request.
