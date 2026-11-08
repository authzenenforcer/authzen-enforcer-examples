# authzen-enforcer-examples

Runnable examples for [authzen-enforcer](https://github.com/authzenenforcer/authzen-enforcer), a
Java SDK that acts as a **Policy Enforcement Point (PEP)** for the
[OpenID AuthZEN Authorization API](https://openid.net/wg/authzen/).

> **Status:** work in progress. The examples are being added; see the list below.
>
> This is an independent project. It is not affiliated with or endorsed by the OpenID Foundation.

## What is here

Every example is a standalone Maven project. You can copy a single directory and build it on its
own; it uses only released versions of authzen-enforcer from Maven Central. The `pom.xml` in the
repository root only builds all examples at once.

All examples talk to the same Policy Decision Point (PDP): a [Keycloak](https://www.keycloak.org/)
container with the experimental AuthZEN feature enabled and a realm prepared for these examples.

| Example | Shows | Status |
|---|---|---|
| `plain-java` | Metadata discovery, single and batch evaluations, a credential provider, fail-closed decisions | Planned |
| `micrometer` | Decision and PDP call metrics with Micrometer | Planned |
| `opentelemetry` | Tracing and `traceparent` propagation with OpenTelemetry | Planned |

Spring Boot and Quarkus examples will follow when the framework integrations are released.

## Requirements

- Java 17 or later
- Maven 3.9 or later
- Docker with Docker Compose

## License

[Apache License 2.0](LICENSE)
