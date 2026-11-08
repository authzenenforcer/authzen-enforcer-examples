# plain-java

authzen-enforcer in a plain Java application, without a framework. The only dependency is
`authzen-enforcer-core`, which itself needs nothing but the JDK.

## Running it

Start the PDP from the repository root as described in the [main README](../README.md), then, in
the same shell:

```bash
mvn -q compile exec:java
```

The defaults fit the Keycloak of this repository. To use a PDP elsewhere, such as your own Keycloak
with the realm imported, override them; see [Configuration](#configuration):

```bash
mvn -q compile exec:java -Dpdp.url=https://keycloak.example.com/realms/authzen-examples
```

The program prints every decision it asks for and exits with status 1 if one differs from what the
realm should decide. You get output similar to this; the request IDs differ on every run:

```text
Metadata discovery
  PDP:                  http://127.0.0.1:8080/realms/authzen-examples
  evaluation endpoint:  http://127.0.0.1:8080/realms/authzen-examples/authzen/access/v1/evaluation
  evaluations endpoint: http://127.0.0.1:8080/realms/authzen-examples/authzen/access/v1/evaluations

Single decisions
  permit bob reads the handbook
  deny   erin, who has no role, reads the handbook
  permit bob updates the handbook
  deny   bob deletes the handbook
  permit alice deletes the handbook

Attributes of the resource: resource.properties
  permit bob updates the release notes while they are a draft
  deny   bob updates the release notes after they are published

Attributes of the environment: context
  permit dave (hr) reads the salary report after MFA
  deny   dave (hr) reads the salary report without MFA
  deny   alice (admin) reads the salary report after MFA

The document collection
  permit carol lists the documents
  deny   carol creates a document
  permit bob creates a document

Several decisions in one call: Access Evaluations
  execute_all: which actions does bob see on the handbook?
  permit bob reads
  permit bob updates
  deny   bob deletes
  deny_on_first_deny: carol exports three documents (2 of 3 answered)
  deny   carol exports all three
         the PDP's context for the deny: {reason=deny_on_first_deny}
  permit_on_first_permit: may dave read any of three documents? (2 of 3 answered)
  permit dave reads at least one

A client as the subject
  permit the portal reads the handbook for its search index
  deny   the portal updates the handbook

Asynchronous calls
  permit carol reads the handbook (evaluateAsync)

Fail-closed: no decision means deny
  deny   alice reads the handbook, but the PDP rejects the PEP's token
         PdpAuthenticationException: PDP rejected the credentials of the PEP with HTTP 401 (X-Request-ID 32770c81-23ff-43ad-83ae-5d640554795e)
  deny   alice reads the handbook, but the PDP is unreachable
         PdpCommunicationException: PDP request failed (X-Request-ID f15a574b-a43b-48d2-becc-6566a5c21ff3)

The provider requested 1 token(s) for all of the calls above.
All decisions are as expected.
```

## What it shows

[`PlainJavaExample`](src/main/java/io/github/authzenenforcer/examples/plainjava/PlainJavaExample.java)
goes through these steps in order:

| Step | AuthZEN feature |
|---|---|
| Metadata discovery | The client gets the PDP URL only and finds the endpoints in `/.well-known/authzen-configuration`. |
| Single decisions | Access Evaluation. A deny is a regular answer with `"decision": false`, not an error. |
| Attributes of the resource | `resource.properties`: the release notes can be updated while their `status` is `draft`. |
| Attributes of the environment | `context`: the salary report needs `"mfa": true`. |
| The document collection | `list` and `create` on `{"type": "collection", "id": "document"}`. |
| Several decisions in one call | Access Evaluations with `execute_all`, `deny_on_first_deny` and `permit_on_first_permit`, and the PDP's reason for a deny. |
| A client as the subject | `{"type": "client", "id": "document-portal"}`: the portal asks for itself. |
| Asynchronous calls | `evaluateAsync`, with a failure turned into a deny. |
| Fail-closed | An invalid PEP token (`PdpAuthenticationException`) and an unreachable PDP (`PdpCommunicationException`) both end in a deny. |

### Fail-closed in application code

The client throws an `AuthZenException` when it gets no decision. The example wraps every call the
way application code should, so that no decision means deny:

```java
static boolean isPermitted(AuthZenClient client, EvaluationRequest request) {
    try {
        return client.evaluate(request).getDecision();
    } catch (AuthZenException e) {
        LOG.log(Level.WARNING, "No decision from the PDP, denying: {0}", e.toString());
        return false;
    }
}
```

### The credential provider

authzen-enforcer never talks to an authorization server. The application supplies the token
through a `CredentialProvider`, which the client calls before every request.
[`ClientCredentialsTokens`](src/main/java/io/github/authzenenforcer/examples/plainjava/ClientCredentialsTokens.java)
obtains the token of the `document-portal` client with the client credentials grant, using only
`java.net.http`. It caches the token until shortly before it expires, lets one caller refresh it at
a time, and drops it when the PDP rejects it. At the end, the program prints how many tokens it
requested: one for all of the calls.

In a framework application, delegate this to the framework's OAuth client instead.

## Configuration

The settings are Maven properties with defaults in [`pom.xml`](pom.xml); `-D` on the command line
overrides them. The client secret is the exception: it is read from the environment only, because
the shell history and the process list would show a value given on the command line.

| Setting | Default | Meaning |
|---|---|---|
| `pdp.url` | `http://127.0.0.1:8080/realms/authzen-examples` | The PDP; with Keycloak, the realm |
| `token.url` | `${pdp.url}/protocol/openid-connect/token` | The token endpoint for the client credentials grant |
| `client.id` | `document-portal` | The PEP's client |
| `unreachable.pdp.url` | `http://127.0.0.1:9` | An address where no PDP answers, for the fail-closed step |
| `DOCUMENT_PORTAL_CLIENT_SECRET` (environment) | none, required | The secret of the PEP's client |

The expected decisions are those of the `authzen-examples` realm, so another PDP needs the same
scenario.
