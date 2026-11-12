# authzen-enforcer-examples

Runnable examples for [authzen-enforcer](https://github.com/authzenenforcer/authzen-enforcer), a
Java SDK that acts as a **Policy Enforcement Point (PEP)** for the
[OpenID AuthZEN Authorization API](https://openid.net/wg/authzen/).

> **Status:** work in progress. The examples are being added; see the list below.
>
> This is an independent project. It is not affiliated with or endorsed by the OpenID Foundation.

## What is here

Every example is a standalone Maven project. You can copy a single directory and build it on its
own; it uses the latest release of authzen-enforcer from Maven Central. The `pom.xml` in the
repository root only builds all examples at once.

All examples talk to the same Policy Decision Point (PDP): a [Keycloak](https://www.keycloak.org/)
container with the experimental AuthZEN feature enabled and a realm prepared for these examples.

| Example | Shows | Status |
|---|---|---|
| [`plain-java`](plain-java) | Metadata discovery, single and batch evaluations, a credential provider, fail-closed decisions | Available |
| [`spring-boot-mvc`](spring-boot-mvc) | `@CheckPermission` in Spring MVC with Spring Security: inferred actions and resources, explicit values, context from claims | Available |
| `micrometer` | Decision and PDP call metrics with Micrometer | Planned |
| `opentelemetry` | Tracing and `traceparent` propagation with OpenTelemetry | Planned |

A Quarkus example will follow when that integration is released.

## Requirements

- Java 17 or later
- Maven 3.9 or later
- Docker with Docker Compose
- `curl` and `jq` for the decision check script

## Starting the PDP

```bash
export DOCUMENT_PORTAL_CLIENT_SECRET=$(openssl rand -hex 24)
export EXAMPLE_USER_PASSWORD=$(openssl rand -hex 24)
docker compose up --wait
```

Keycloak listens on `http://127.0.0.1:8080`; `KEYCLOAK_ADDRESS` and `KEYCLOAK_PORT` change the
address and the port. The realm file
contains no secrets: Keycloak reads the client secret and the users' password from the environment
when it imports the realm, and the examples read them from the same variables. The realm is imported at the first start only;
`docker compose down` removes the container, and the next start imports the file again.

To look at the realm in the admin console, also set an administrator before the first start; it is
only created then, so a running Keycloak needs `docker compose down` first:

```bash
export KC_BOOTSTRAP_ADMIN_USERNAME=admin
export KC_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -hex 12)
```

The console is at `http://127.0.0.1:8080/admin` (or the port in `KEYCLOAK_PORT`). The decisions
come from the authorization settings of the client `document-portal`: realm `authzen-examples` →
Clients → `document-portal` → Authorization.

To check that the PDP makes the decisions described below:

```bash
keycloak/check-decisions.sh
```

The script follows `KEYCLOAK_PORT`; for a PDP elsewhere, set `PDP_URL` (the realm URL), and
`TOKEN_URL` or `CLIENT_ID` if they differ from Keycloak's defaults.

`docker compose down` stops Keycloak.

## Running an example

With the PDP running and `DOCUMENT_PORTAL_CLIENT_SECRET` set in the same shell:

```bash
mvn -q -f plain-java compile exec:java
```

The `spring-boot-mvc` example is an application that keeps running; its script starts the PDP as
well and keeps the secrets in `.env`, see [its README](spring-boot-mvc/README.md):

```bash
spring-boot-mvc/run.sh
```

Each example compares the decisions it gets with the ones the realm should make and exits with
status 1 if any differ. The PDP URL and the other settings have defaults that fit this Keycloak;
`-D` on the command line overrides them, as each example's README describes. `mvn verify` in the
repository root builds all examples.

## The scenario

The examples play the backend of a company's document portal. The backend is the PEP: it asks the
PDP whether a user may perform an action on a document. The realm `authzen-examples`
([`keycloak/authzen-examples-realm.json`](keycloak/authzen-examples-realm.json)) contains:

**The PEP.** The confidential client `document-portal`. It obtains its access token with the client
credentials grant and calls the AuthZEN endpoints with it.

**The users' client.** The public client `documents-cli` signs the users in with the password grant,
for the `spring-boot-mvc` example's decision check only. All users share the password in
`EXAMPLE_USER_PASSWORD`.

**Users.**

| User | Role |
|---|---|
| `alice` | `admin` |
| `bob` | `editor` |
| `carol` | `viewer` |
| `dave` | `hr` |
| `erin` | no role |

**Resources and permissions.** Documents have the type `document`; the collection of documents is
`{"type": "collection", "id": "document"}`. Actions follow the authzen-enforcer convention: `read`,
`list`, `create`, `update` and `delete`.

| Resource | Action | Allowed for |
|---|---|---|
| `document:handbook` | `read` | every role |
| | `update` | `editor`, `admin` |
| | `delete` | `admin` |
| `document:release-notes` | `read` | every role |
| | `update` | `editor`, `admin`, while `resource.properties.status` is `draft` |
| | `delete` | `admin` |
| `document:salary-report` | `read`, `update` | `hr`, when `context.mfa` is `true` |
| | `delete` | `admin` |
| `collection:document` | `list` | every role |
| | `create` | `editor`, `admin` |

The service account of `document-portal` has the `viewer` role, so the portal itself, as the subject
`{"type": "client", "id": "document-portal"}`, can read the handbook and the release notes. This
stands for work that the portal does on its own behalf, such as building a search index.

Anything not listed is denied. That includes unknown users, resources and actions: Keycloak answers
them with `"decision": false`, not with an error.

## Keycloak as an AuthZEN PDP

AuthZEN support in Keycloak is experimental (`--features=authzen`, since 26.7). Dependabot proposes
new Keycloak versions for `compose.yaml`, and CI runs the decision check and the examples against
them. The behaviour below was checked with Keycloak 26.8.1 and affects how the examples are written:

- **The endpoints are below `/realms/{realm}/authzen`.** Use metadata discovery, which finds them;
  the default paths of the specification do not apply.
- **Only the Access Evaluation and Access Evaluations APIs** are available. There are no Search
  APIs, so the examples do not use them.
- **`resource.type` must equal the type of the Keycloak resource**, and `resource.id` is the
  resource's name. `action.name` is a scope name.
- **A client subject can only be the calling client.** A request with `subject.type` `client` is
  evaluated only when `subject.id` is the client ID of the PEP that sends it; any other client is
  denied.
- **`resource.properties` and `context` share one set of attributes.** Policies cannot tell which
  of the two a value came from, and the context wins when both use the same name
  ([keycloak/keycloak#54848](https://github.com/keycloak/keycloak/issues/54848)). The realm
  therefore uses different names (`status` for the resource, `mfa` for the context).
- **`action.properties` and unknown fields are rejected** with `400`
  ([keycloak/keycloak#54542](https://github.com/keycloak/keycloak/issues/54542)), so the examples do
  not send them.
- **`subject.properties` override the user's attributes** in Keycloak
  ([keycloak/keycloak#53328](https://github.com/keycloak/keycloak/issues/53328)). The realm does not
  base any decision on subject attributes.

## License

[Apache License 2.0](LICENSE)
