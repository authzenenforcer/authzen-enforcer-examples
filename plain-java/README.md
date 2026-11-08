# plain-java

authzen-enforcer in a plain Java application, without a framework. The only dependency is
`authzen-enforcer-core`, which itself needs nothing but the JDK.

## Running it

Start the PDP from the repository root as described in the [main README](../README.md), then, in
the same shell:

```bash
mvn -q compile exec:java
```

The program prints every decision it asks for and exits with status 1 if one differs from what the
realm should decide.

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

| Variable | Default | Meaning |
|---|---|---|
| `DOCUMENT_PORTAL_CLIENT_SECRET` | none, required | The client secret Keycloak was started with |
| `KEYCLOAK_URL` | `http://127.0.0.1:${KEYCLOAK_PORT}` | Where Keycloak runs |
| `KEYCLOAK_PORT` | `8080` | Used only when `KEYCLOAK_URL` is not set |
