# spring-boot-webflux

authzen-enforcer in a Spring Boot 4 application with Spring WebFlux and Spring Security. The starter
`authzen-enforcer-spring-boot-starter` asks the PDP before every handler annotated with
`@CheckPermission` and answers with `401` or `403` when it may not run.

Spring Boot offers two web stacks: Spring MVC on servlets and the reactive Spring WebFlux, used
here. This example mirrors [spring-boot-mvc](../spring-boot-mvc): the same endpoints, annotations,
settings and decision check, with the same results. What differs is listed under
[Reactive](#reactive).

> The starter is a preview, available since authzen-enforcer 0.2.0. This example uses 0.4.0.

## What it shows

- **Zero configuration for the PDP.** `authzen.pdp.url` is the only setting the starter needs; it
  finds the endpoints in the PDP's metadata.
- **Inference.** [`ReactiveDocumentController`](src/main/java/io/github/authzenenforcer/examples/springbootwebflux/ReactiveDocumentController.java)
  names no action and no resource. `GET /documents/handbook` becomes `read` on
  `{"type": "document", "id": "handbook"}`, `GET /documents` becomes `list` on
  `{"type": "collection", "id": "document"}`, and the subject is the caller of the access token.
- **Explicit values win over inference.** `GET /documents/{id}/download` would be inferred as listing
  a nested collection; `@CheckPermission(action = "read", resourceType = "document", resourceId = "#id")`
  on that handler makes it a `read` of the document instead.
- **Context from claims.** Reading a document sends `context.mfa`, derived from the `acr` claim
  with an expression. Expressions can read path variables, a few values of the request and the
  caller's claims, never the caller's token.
- **A batch with `deny_on_first_deny`.**
  [`ReactiveBulkDeleteController`](src/main/java/io/github/authzenenforcer/examples/springbootwebflux/ReactiveBulkDeleteController.java)
  deletes several documents at once, all or none. `@CheckPermission` asks about one resource, so
  the handler asks the PDP itself, with the starter's `AuthZenClient` and `SubjectResolver`: one
  Access Evaluations request with a `delete` for every document and
  `options.evaluations_semantic` set to `deny_on_first_deny`, so the PDP stops at the first
  document the caller may not delete. A decision it did not return counts as a deny.
- **Filtering a list.** The `list` decision on `GET /documents` is about the collection, not about
  the documents in it: it lets the handler run, and the handler decides what it returns. So
  `ReactiveDocumentController` asks the PDP about every document in one Access Evaluations request, with
  the same `read`, subject and context as reading one, and returns only the permitted ones. A
  decision per document needs a PDP that can decide about each of them; this realm knows only the
  documents it was created with, so a document created later is never listed. With pagination,
  filter before paging, or the pages come back short and the totals count documents the caller
  cannot see.
- **The PEP's own identity.** The application authenticates to the PDP as the confidential client
  `document-portal`, with the client credentials grant. `authzen.pdp.client-registration-id` names
  the registration of Spring Security's OAuth 2.0 client that obtains, caches and renews the token,
  and `ReactiveDocumentsApplication` adds connect and response timeouts to its token requests. The starter never forwards the
  caller's token.
- **Tokens issued for this API only.** Spring Security accepts an access token of the realm only
  if its audience is `documents-api`, which the realm adds to the tokens of `documents-cli`. Any
  other token of the realm, such as the one the PEP obtains for the PDP, gets `401`.
- **Answers.** No token, or a token not issued for this API: `401` with Spring Security's
  challenge. A deny or a PDP failure: `403`.
- **Metrics and health.** With `authzen-enforcer-micrometer` and Spring Boot Actuator on the
  classpath, the starter records the meters of the PDP client and of the enforcer by itself; no
  code is needed. Actuator answers on a port of its own, `9082`, bound to `127.0.0.1`, so neither
  the metrics nor the health details are reachable through the API. The opt-in `authzen` health
  indicator shows the state of the client's circuit breakers and of the last metadata refresh,
  without calling the PDP. See [Metrics and health](#metrics-and-health).

## Running it

From the repository root:

```bash
spring-boot-webflux/run.sh         # start Keycloak and the application, and show a few calls
spring-boot-webflux/run.sh stop    # stop both
```

[`run.sh`](run.sh) starts the Keycloak of this repository, builds the application, starts it in
the background on port `8082` and calls it as a few users:

```text
Calls to the application, http://127.0.0.1:8082:
  200 bob reads the handbook
  403 bob deletes the handbook (only admins may)
  401 a caller without a token reads the handbook
```

Both keep running until `run.sh stop`, so that you can make your own calls; the script prints how.
The log of the application is in `app-webflux.log`.

The secrets of the realm, the client secret of the PEP and the users' password, are in `.env` in
the repository root. The first run creates it with random values; Git ignores it, and Docker
Compose, the application and the script read it, so they always agree. For your own calls, load it
into your shell:

```bash
set -a; . ./.env; set +a
TOKEN=$(curl -s http://127.0.0.1:$KEYCLOAK_PORT/realms/authzen-examples/protocol/openid-connect/token \
  -d grant_type=password -d client_id=documents-cli -d username=carol \
  --data-urlencode "password=$EXAMPLE_USER_PASSWORD" | jq -r .access_token)
curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8082/documents
```

Keycloak issues the tokens and makes the decisions; the application serves the documents API. The
users are `alice` (admin), `bob` (editor), `carol` (viewer), `dave` (hr) and `erin` (no role); see
the [main README](../README.md#the-scenario).

| Setting | |
|---|---|
| `KEYCLOAK_PORT=8180 spring-boot-webflux/run.sh` | Keycloak on another port than `8080`, for example when it is taken; kept in `.env` |
| `DEBUG=1 spring-boot-webflux/run.sh` | the application with a debugger port on `127.0.0.1:5005`, for your IDE's remote debugging |
| `MANAGEMENT_SERVER_PORT=9181 spring-boot-webflux/run.sh` | Actuator on another port than `9082`, for example when it is taken |

`documents-cli` is a public client with the password grant, which the realm has for this example
only. A real client signs users in with the authorization code flow.

### The decision check

`spring-boot-webflux/run.sh check` runs the application with `--example.check=true`: it calls its own
endpoints as the users of the realm and prints every status. It then checks Actuator and prints the
meters these calls recorded, and exits with status 1 if a status or a meter differs from the
expected one. CI runs it.

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
  401 the PEP's own token reads the handbook

The document collection
  200 carol lists the documents: [handbook, release-notes]
  403 erin, who has no role, lists the documents
  403 carol creates a document
  201 bob creates a document
  200 bob lists the documents, without his new one: [handbook, release-notes]
  204 alice deletes the handbook

Several documents at once
  403 bob deletes the release notes and the salary report
  403 alice deletes the release notes and bob's new document
  204 alice deletes the release notes and the salary report

Actuator
  200 the authzen health indicator
  200 the meter authzen.enforcements
  404 Actuator on the port of the API

Metrics
  6   authzen.client.calls exception=none operation=evaluation outcome=deny
  7   authzen.client.calls exception=none operation=evaluation outcome=permit
  5   authzen.client.calls exception=none operation=evaluations outcome=completed
  6   authzen.enforcements outcome=denied
  7   authzen.enforcements outcome=permitted
  1   authzen.enforcements outcome=unauthenticated

All statuses and meters are as expected.
```

## Reactive

- **When the starter decides.** A handler adapter ordered first decides after the request is
  mapped and before the handler's arguments are resolved, so the body of a denied request is not
  read. In Spring MVC, a handler interceptor does the same.
- **No blocked event loop.** Every decision starts on Reactor's bounded elastic scheduler, so a
  blocking credential provider or listener cannot stall the event loop. Cancelling the request
  cancels the call to the PDP.
- **The PEP's token.** In a WebFlux application, Spring Boot creates the reactive OAuth 2.0 client,
  and the starter uses it with the same registration: no thread waits for the token request. A
  `ReactiveOAuth2AccessTokenResponseClient` bean gives it timeouts.
- **Reactive handlers.** Every handler returns a `Mono` and reads its body as a `Mono`. The two
  that ask the PDP themselves, `readableDocuments` and `deleteDocuments`, call
  `AuthZenClient.evaluationsAsync`, so no thread waits for the PDP either.
- **Stateless security.** `ReactiveDocumentsApplication` defines a `SecurityWebFilterChain` without a
  session, with the same CSRF rule as the servlet example: requests with an `Authorization` header
  need no CSRF token.
- **Netty on macOS.** At startup, Netty may log an error that it cannot load
  `MacOSDnsServerAddressStreamProvider`. It falls back to the system's DNS settings; the example
  only calls `127.0.0.1`.

## Metrics and health

The starter configures metrics when `authzen-enforcer-micrometer` is present and there is a
`MeterRegistry`, which `spring-boot-starter-actuator` provides. While the application runs:

```bash
curl -s http://127.0.0.1:9082/actuator/metrics | jq '.names[] | select(startswith("authzen"))'
curl -s 'http://127.0.0.1:9082/actuator/metrics/authzen.enforcements?tag=outcome:denied' | jq
curl -s http://127.0.0.1:9082/actuator/health/authzen | jq
```

Without a tag filter, `/actuator/metrics/<name>` adds up the values of every tag combination:
`authzen.enforcements` alone counts all requests, whatever their outcome. Filter with one or more
`tag=key:value` parameters, such as `?tag=operation:evaluation&tag=outcome:deny` on
`authzen.client.calls`; a tag value that no meter has answers `404`.

- `authzen.enforcements` counts the requests the starter handled, by outcome: `permitted`,
  `denied`, `unauthenticated` (no token, no call to the PDP) and others. `failed_open` would count
  requests let through without a decision, which deserves an alert; this example never fails open.
- `authzen.client.calls` counts the calls to the PDP by operation and outcome: one `evaluation` per
  enforcement with a caller, and the `evaluations` of the handlers that ask the PDP themselves.
- `authzen.client.attempts`, `authzen.client.breaker.*`, `authzen.client.metadata.refreshes` and
  the `listener.dropped` counters complete them.

No tag carries a subject, resource or request id, so the number of time series stays bounded.

`/actuator/metrics` shows the current values, for a look at a running application; it keeps no
history. The meters are kept in memory by Spring Boot's default registry. To collect them over time,
add the `micrometer-registry-*` module of your monitoring system: depending on the system, its
registry publishes an endpoint for the system to read or sends the meters itself. Nothing else
changes.

The `authzen` health indicator is opt-in (`management.health.authzen.enabled`). It reads the state
of the client without contacting the PDP, and it is `DOWN` while calls deny at once, for example
while a circuit breaker is open. It counts towards `/actuator/health`, but not towards the liveness
and readiness groups: an outage of the PDP would take every instance out of service at once, while
enforcement already denies on its own.

Actuator listens on `127.0.0.1:9082`, a port of its own, so that the metrics and the health details
stay off the API. Choose another port with `MANAGEMENT_SERVER_PORT` or
`--management.server.port`. On the port of the API, Spring Boot would serve Actuator on every
interface of the API and ignore `management.server.address`, so
[`ReactiveDocumentsApplication`](src/main/java/io/github/authzenenforcer/examples/springbootwebflux/ReactiveDocumentsApplication.java)
refuses to start, with exit status 2, if the two ports are the same or the address is not a
loopback address. Actuator has no authentication here: every user of this machine can read it. On
a shared host, protect it, for example with a security filter chain of its own.

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
| `server.port` | `8082` | Keycloak uses `8080`, the spring-boot-mvc example `8081` |
| `authzen.pdp.client-registration-id` | `pdp` | The registration that obtains the PEP's token |
| `spring.security.oauth2.client.registration.pdp.client-id` | `document-portal` | The PEP's client; its secret comes from `DOCUMENT_PORTAL_CLIENT_SECRET` |
| `management.server.port` | `9082` | Actuator, on `127.0.0.1` only |
| `management.health.authzen.enabled` | `true` | The `authzen` health indicator, off by default in the starter |
| `example.user-client-id` | `documents-cli` | The client the decision check gets user tokens from; the password comes from `EXAMPLE_USER_PASSWORD` |

## Not shown

- `resource.properties`: the starter infers the type and id of a resource, not its attributes, so
  the realm's rule for updating the release notes (`status` must be `draft`) always denies here.
- The client subject (`{"type": "client", "id": "document-portal"}`): the subject is always the
  caller of the request.
