# plain-java

authzen-enforcer in a plain Java application, without a framework. It needs
`authzen-enforcer-core`, which itself needs nothing but the JDK, and records metrics with
`authzen-enforcer-micrometer` and Micrometer.

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

The program prints every decision it asks for and the meters and spans these calls recorded, and
exits with status 1 if a decision differs from what the realm should decide or a meter or span is
missing. You get output similar to this; the request IDs, trace IDs and the breaker's waiting time
differ on every run:

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

Tracing
  permit carol reads the handbook, inside a span of the application
  handle request             trace c92a1135bb800a0b44b1ef3195bc2a58
  └─ authzen evaluation      INTERNAL  authzen.outcome=permit
     └─ POST                 CLIENT    http.response.status_code=200
  traceparent sent to the PDP: 00-c92a1135bb800a0b44b1ef3195bc2a58-32028edbabf98dc0-03
  permit the call to the PDP joined the trace of the application
  20 call spans and 20 exchange spans recorded for the portal client

Fail-closed: no decision means deny
  deny   alice reads the handbook, but the PDP rejects the PEP's token
         PdpAuthenticationException: PDP rejected the credentials of the PEP with HTTP 401 (X-Request-ID 788ad4bf-937c-4b17-b8a9-c54bbb789023)
  deny   alice reads the handbook, but the PDP is unreachable
         PdpCommunicationException: PDP request failed (X-Request-ID 6542a82e-54f4-4d2a-bf85-460ffd1418c0)
  deny   alice reads the handbook, but the PDP is still unreachable
         PdpCommunicationException: PDP request failed (X-Request-ID fe6b6386-573b-416e-a5dc-93b00418fa5e)
  deny   alice reads the handbook; the third failure in a row opens the circuit breaker
         PdpCommunicationException: PDP request failed (X-Request-ID cc5aabfb-c126-42b8-95e9-10fff1048b48)
  deny   alice reads the handbook while the breaker is open
         PdpCommunicationException: PDP metadata discovery is suspended because the PDP kept failing (next attempt in 4206 ms): PDP request failed (X-Request-ID cc5aabfb-c126-42b8-95e9-10fff1048b48)

Metrics
  17   authzen.client.attempts client=portal exception=none next=none operation=evaluation status=200
  3    authzen.client.attempts client=portal exception=none next=none operation=evaluations status=200
  1    authzen.client.attempts client=portal exception=none next=none operation=metadata status=200
  3    authzen.client.attempts client=unreachable exception=ConnectException next=none operation=metadata status=none
  1    authzen.client.attempts client=wrong-token exception=none next=none operation=evaluation status=401
  1    authzen.client.attempts client=wrong-token exception=none next=none operation=metadata status=200
  1    authzen.client.attempts client=wrong-token exception=none next=refresh_credential operation=evaluation status=401
  0    authzen.client.breaker.state breaker=authorization_api client=portal
  0    authzen.client.breaker.state breaker=authorization_api client=unreachable
  0    authzen.client.breaker.state breaker=authorization_api client=wrong-token
  0    authzen.client.breaker.state breaker=metadata_discovery client=portal
  2    authzen.client.breaker.state breaker=metadata_discovery client=unreachable
  0    authzen.client.breaker.state breaker=metadata_discovery client=wrong-token
  1    authzen.client.breaker.transitions breaker=metadata_discovery client=unreachable from=closed to=open
  7    authzen.client.calls client=portal exception=none operation=evaluation outcome=deny
  10   authzen.client.calls client=portal exception=none operation=evaluation outcome=permit
  3    authzen.client.calls client=portal exception=none operation=evaluations outcome=completed
  4    authzen.client.calls client=unreachable exception=PdpCommunicationException operation=evaluation outcome=failed
  1    authzen.client.calls client=wrong-token exception=PdpAuthenticationException operation=evaluation outcome=failed
  0    authzen.client.listener.dropped client=portal
  0    authzen.client.listener.dropped client=unreachable
  0    authzen.client.listener.dropped client=wrong-token
  1    authzen.client.metadata.refreshes client=portal result=fresh
  3    authzen.client.metadata.refreshes client=unreachable result=failed
  1    authzen.client.metadata.refreshes client=wrong-token result=fresh

The provider requested 1 token(s) for all of the calls above.
All decisions, meters and spans are as expected.
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
| Tracing | The spans of `authzen-enforcer-opentelemetry`: a call inside a span of the application joins its trace, and the `traceparent` header sent to the PDP carries that trace. |
| Fail-closed | An invalid PEP token (`PdpAuthenticationException`) and an unreachable PDP (`PdpCommunicationException`) both end in a deny. After three failures in a row, the circuit breaker opens and the next call fails at once, without contacting the PDP. |
| Metrics | The meters of `authzen-enforcer-micrometer` for all of the calls above. |

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

### Metrics

`authzen-enforcer-micrometer` turns the events of a client into Micrometer meters. Every client
gets its own `MicrometerPepListener`, and `AuthZenClientMetrics` exposes the events it had to drop:

```java
Tags tags = Tags.of("client", "portal");
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl(pdpUrl)
        .credentials(tokens)
        .listener(MicrometerPepListener.builder(registry).tags(tags).build())
        .build();
AuthZenClientMetrics.bind(client, registry, tags);
```

The example has three clients: the portal, one with an invalid token and one for an unreachable
PDP. They share the registry, so each listener has its own `client` tag; with the same tags, the
meters of one would mix with those of another. All of them use the same tag keys, which some
monitoring systems require.

- `authzen.client.calls` counts calls by operation and outcome: `permit` and `deny` for a single
  decision, `completed` for a batch, `failed` with the exception's class when there was no
  decision.
- `authzen.client.attempts` counts the HTTP exchanges with the PDP, discovery included, by status.
  `next=refresh_credential` shows the retry after the PDP rejected the token.
- `authzen.client.breaker.transitions` and `authzen.client.breaker.state` show the breaker of the
  unreachable client opening: `0` closed, `1` half-open, `2` open.
- `authzen.client.metadata.refreshes` counts discoveries by result.
- `authzen.client.listener.dropped` counts the events a listener was too slow for. The example
  sums them per client; the meters themselves are per listener and event type.

No tag carries a subject, resource or request id, so the number of time series stays bounded.

Listeners get the events on a thread of their own, shortly after the calls, so the example waits
for them before it prints the meters. The meters are kept in memory by a `SimpleMeterRegistry`;
to send them to your monitoring system, use the registry of its `micrometer-registry-*` module
instead. Nothing else changes.

### Tracing

`authzen-enforcer-opentelemetry` records every call of a client as a span, with a child span for
each HTTP exchange with the PDP, and sends the exchange's trace context to the PDP in the W3C
`traceparent` header. The client gets a tracer built from the application's `OpenTelemetry`:

```java
AuthZenClient client = AuthZenClient.builder()
        .pdpUrl(pdpUrl)
        .credentials(tokens)
        .tracer(OpenTelemetryTracing.builder(openTelemetry).build())
        .build();
```

A call starts in the span that is current on its thread, such as the span of the request the
application is handling, so its spans join that trace:

```text
handle request             the application's span
└─ authzen evaluation      INTERNAL, one per call, with the operation, action, resource type and outcome
   └─ POST                 CLIENT, one per exchange, with the status and the X-Request-ID
```

The PDP gets `traceparent: 00-<trace id of the application>-<span id of POST>-...`, so a PDP that
records traces, such as Keycloak with tracing enabled, adds its own spans under the exchange. The
example checks both: the call's spans have the application's span as their ancestor, and the
`traceparent` that the client sent, which a `PepListener` reads from `AttemptCompleted`, names the
same trace and the exchange's span.

The spans carry no subject id, no resource id and no property or context value. The SDK keeps
them in memory here, with a `SpanExporter` of the example; an application exports them to its
tracing backend instead, for example with the OTLP exporter of the OpenTelemetry SDK. Only the
portal client is traced: a client without a tracer records no spans and sends no `traceparent`.

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
