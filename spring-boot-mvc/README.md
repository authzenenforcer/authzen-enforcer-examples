# spring-boot-mvc

authzen-enforcer in a Spring Boot 4 application with Spring MVC and Spring Security. The starter
`authzen-enforcer-spring-boot-starter` asks the PDP before every handler annotated with
`@CheckPermission` and answers with `401` or `403` when it may not run.

Spring Boot offers two web stacks: Spring MVC on servlets, used here, and the reactive Spring
WebFlux. The starter supports both; [spring-boot-webflux](../spring-boot-webflux) is the same
application on WebFlux.

> The starter is a preview, available since authzen-enforcer 0.2.0. This example uses 0.3.0.

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
- **A batch with `deny_on_first_deny`.**
  [`BulkDeleteController`](src/main/java/io/github/authzenenforcer/examples/springbootmvc/BulkDeleteController.java)
  deletes several documents at once, all or none. `@CheckPermission` asks about one resource, so
  the handler asks the PDP itself, with the starter's `AuthZenClient` and `SubjectResolver`: one
  Access Evaluations request with a `delete` for every document and
  `options.evaluations_semantic` set to `deny_on_first_deny`, so the PDP stops at the first
  document the caller may not delete. A decision it did not return counts as a deny.
- **Filtering a list.** The `list` decision on `GET /documents` is about the collection, not about
  the documents in it: it lets the handler run, and the handler decides what it returns. So
  `DocumentController` asks the PDP about every document in one Access Evaluations request, with
  the same `read`, subject and context as reading one, and returns only the permitted ones. A
  decision per document needs a PDP that can decide about each of them; this realm knows only the
  documents it was created with, so a document created later is never listed. With pagination,
  filter before paging, or the pages come back short and the totals count documents the caller
  cannot see.
- **The PEP's own identity.** The application authenticates to the PDP as the confidential client
  `document-portal`, with the client credentials grant. `authzen.pdp.client-registration-id` names
  the registration of Spring Security's OAuth 2.0 client that obtains, caches and renews the token,
  and `DocumentsApplication` adds timeouts to its token requests. The starter never forwards the
  caller's token.
- **Tokens issued for this API only.** Spring Security accepts an access token of the realm only
  if its audience is `documents-api`, which the realm adds to the tokens of `documents-cli`. Any
  other token of the realm, such as the one the PEP obtains for the PDP, gets `401`.
- **Answers.** No token, or a token not issued for this API: `401` with Spring Security's
  challenge. A deny or a PDP failure: `403`.

## Running it

From the repository root:

```bash
spring-boot-mvc/run.sh         # start Keycloak and the application, and show a few calls
spring-boot-mvc/run.sh stop    # stop both
```

[`run.sh`](run.sh) starts the Keycloak of this repository, builds the application, starts it in
the background on port `8081` and calls it as a few users:

```text
Calls to the application, http://127.0.0.1:8081:
  200 bob reads the handbook
  403 bob deletes the handbook (only admins may)
  401 a caller without a token reads the handbook
```

Both keep running until `run.sh stop`, so that you can make your own calls; the script prints how.
The log of the application is in `app.log`.

The secrets of the realm, the client secret of the PEP and the users' password, are in `.env` in
the repository root. The first run creates it with random values; Git ignores it, and Docker
Compose, the application and the script read it, so they always agree. For your own calls, load it
into your shell:

```bash
set -a; . ./.env; set +a
TOKEN=$(curl -s http://127.0.0.1:$KEYCLOAK_PORT/realms/authzen-examples/protocol/openid-connect/token \
  -d grant_type=password -d client_id=documents-cli -d username=carol \
  --data-urlencode "password=$EXAMPLE_USER_PASSWORD" | jq -r .access_token)
curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8081/documents
```

Keycloak issues the tokens and makes the decisions; the application serves the documents API. The
users are `alice` (admin), `bob` (editor), `carol` (viewer), `dave` (hr) and `erin` (no role); see
the [main README](../README.md#the-scenario).

| Setting | |
|---|---|
| `KEYCLOAK_PORT=8180 spring-boot-mvc/run.sh` | Keycloak on another port than `8080`, for example when it is taken; kept in `.env` |
| `DEBUG=1 spring-boot-mvc/run.sh` | the application with a debugger port on `127.0.0.1:5005`, for your IDE's remote debugging |

`documents-cli` is a public client with the password grant, which the realm has for this example
only. A real client signs users in with the authorization code flow.

### The decision check

`spring-boot-mvc/run.sh check` runs the application with `--example.check=true`: it calls its own
endpoints as the users of the realm, prints every status and exits with status 1 if one differs
from the expected one. CI runs it.

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
| `authzen.pdp.client-registration-id` | `pdp` | The registration that obtains the PEP's token |
| `spring.security.oauth2.client.registration.pdp.client-id` | `document-portal` | The PEP's client; its secret comes from `DOCUMENT_PORTAL_CLIENT_SECRET` |
| `example.user-client-id` | `documents-cli` | The client the decision check gets user tokens from; the password comes from `EXAMPLE_USER_PASSWORD` |

## Not shown

- `resource.properties`: the starter infers the type and id of a resource, not its attributes, so
  the realm's rule for updating the release notes (`status` must be `draft`) always denies here.
- The client subject (`{"type": "client", "id": "document-portal"}`): the subject is always the
  caller of the request.
