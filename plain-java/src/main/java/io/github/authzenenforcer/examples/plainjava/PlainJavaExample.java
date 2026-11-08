package io.github.authzenenforcer.examples.plainjava;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.PdpAuthenticationException;
import io.github.authzenenforcer.core.PdpCommunicationException;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationRequest;
import io.github.authzenenforcer.core.model.EvaluationResponse;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.EvaluationsSemantic;
import io.github.authzenenforcer.core.model.PdpMetadata;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.core.model.Subject;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Walks through the AuthZEN features that authzen-enforcer supports, against the Keycloak PDP of
 * this repository.
 *
 * <p>Every decision is compared with the one the realm should make. The program exits with status
 * 1 if any of them differs, so that it doubles as a check in CI.
 */
public final class PlainJavaExample {

    private static final Logger LOG = System.getLogger(PlainJavaExample.class.getName());
    private static final String CLIENT_SECRET_VARIABLE = "DOCUMENT_PORTAL_CLIENT_SECRET";

    private static final Resource HANDBOOK = Resource.of("document", "handbook");
    private static final Resource SALARY_REPORT = Resource.of("document", "salary-report");
    private static final Resource DOCUMENTS = Resource.of("collection", "document");

    private final AuthZenClient client;
    private int unexpected;

    private PlainJavaExample(AuthZenClient client) {
        this.client = client;
    }

    public static void main(String[] args) {
        // Set in pom.xml; override them on the command line, for example -Dpdp.url=...
        String pdpUrl = requiredProperty("pdp.url");
        String tokenUrl = requiredProperty("token.url");
        String clientId = requiredProperty("client.id");
        String unreachablePdpUrl = requiredProperty("unreachable.pdp.url");
        // The secret comes from the environment, never from the command line, where the shell
        // history and the process list would show it
        String clientSecret = System.getenv(CLIENT_SECRET_VARIABLE);
        if (clientSecret == null || clientSecret.isEmpty()) {
            System.err.println("Set " + CLIENT_SECRET_VARIABLE + " to the secret of the client " + clientId);
            System.exit(2);
        }

        ClientCredentialsTokens tokens = new ClientCredentialsTokens(URI.create(tokenUrl), clientId, clientSecret);

        // The PDP URL and the credentials are all the client needs. It finds the endpoints in the
        // metadata the PDP publishes. Create one client per PDP and share it: it is thread-safe.
        AuthZenClient client = AuthZenClient.builder()
                .pdpUrl(pdpUrl)
                .credentials(tokens)
                .build();

        PlainJavaExample example = new PlainJavaExample(client);
        example.discovery();
        example.singleDecisions();
        example.resourceProperties();
        example.context();
        example.collection();
        example.batch();
        example.clientSubject(clientId);
        example.asynchronous();
        example.failClosed(pdpUrl, unreachablePdpUrl);

        System.out.printf("%nThe provider requested %d token(s) for all of the calls above.%n",
                tokens.tokenRequests());
        if (example.unexpected > 0) {
            System.out.printf("%d decision(s) differ from the expected ones.%n", example.unexpected);
            System.exit(1);
        }
        System.out.println("All decisions are as expected.");
    }

    private void discovery() {
        section("Metadata discovery");
        Optional<PdpMetadata> metadata = client.metadata();
        metadata.ifPresent(m -> {
            System.out.println("  PDP:                  " + m.getPolicyDecisionPoint());
            System.out.println("  evaluation endpoint:  " + m.getAccessEvaluationEndpoint());
            m.getAccessEvaluationsEndpoint().ifPresent(e -> System.out.println("  evaluations endpoint: " + e));
        });
        if (metadata.isEmpty()) {
            System.out.println("  The PDP publishes no metadata; the client uses the default paths.");
        }
    }

    private void singleDecisions() {
        section("Single decisions");
        // A deny is a regular answer, not an error
        expect("bob reads the handbook", true, user("bob"), "read", HANDBOOK);
        expect("erin, who has no role, reads the handbook", false, user("erin"), "read", HANDBOOK);
        expect("bob updates the handbook", true, user("bob"), "update", HANDBOOK);
        expect("bob deletes the handbook", false, user("bob"), "delete", HANDBOOK);
        expect("alice deletes the handbook", true, user("alice"), "delete", HANDBOOK);
    }

    private void resourceProperties() {
        section("Attributes of the resource: resource.properties");
        expect("bob updates the release notes while they are a draft", true,
                user("bob"), "update", releaseNotes("draft"));
        expect("bob updates the release notes after they are published", false,
                user("bob"), "update", releaseNotes("published"));
    }

    private void context() {
        section("Attributes of the environment: context");
        Context withMfa = Context.builder().put("mfa", true).build();
        expect("dave (hr) reads the salary report after MFA", true,
                EvaluationRequest.builder().subject(user("dave")).action(Action.of("read"))
                        .resource(SALARY_REPORT).context(withMfa).build());
        expect("dave (hr) reads the salary report without MFA", false,
                user("dave"), "read", SALARY_REPORT);
        expect("alice (admin) reads the salary report after MFA", false,
                EvaluationRequest.builder().subject(user("alice")).action(Action.of("read"))
                        .resource(SALARY_REPORT).context(withMfa).build());
    }

    private void collection() {
        section("The document collection");
        expect("carol lists the documents", true, user("carol"), "list", DOCUMENTS);
        expect("carol creates a document", false, user("carol"), "create", DOCUMENTS);
        expect("bob creates a document", true, user("bob"), "create", DOCUMENTS);
    }

    private void batch() {
        section("Several decisions in one call: Access Evaluations");

        // execute_all (the default): one answer per item, for example to decide which buttons
        // the UI shows. The top-level subject and resource are defaults for every item.
        EvaluationsResponse buttons = evaluations(client, EvaluationsRequest.builder()
                .subject(user("bob"))
                .resource(HANDBOOK)
                .evaluation(EvaluationItem.builder().action(Action.of("read")).build())
                .evaluation(EvaluationItem.builder().action(Action.of("update")).build())
                .evaluation(EvaluationItem.builder().action(Action.of("delete")).build())
                .build());
        System.out.println("  execute_all: which actions does bob see on the handbook?");
        report("bob reads", true, buttons.isPermitted(0));
        report("bob updates", true, buttons.isPermitted(1));
        report("bob deletes", false, buttons.isPermitted(2));

        // deny_on_first_deny: all or nothing, for example a bulk export. The PDP stops at the
        // first deny; the items after it count as denies.
        EvaluationsResponse export = evaluations(client, EvaluationsRequest.builder()
                .subject(user("carol"))
                .action(Action.of("read"))
                .evaluation(EvaluationItem.builder().resource(HANDBOOK).build())
                .evaluation(EvaluationItem.builder().resource(SALARY_REPORT).build())
                .evaluation(EvaluationItem.builder().resource(releaseNotes("published")).build())
                .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY)
                .build());
        System.out.printf("  deny_on_first_deny: carol exports three documents (%d of %d answered)%n",
                export.getEvaluations().size(), export.getRequested());
        report("carol exports all three", false, export.allPermitted());
        export.get(1).flatMap(EvaluationResponse::getContext)
                .ifPresent(c -> System.out.println("         the PDP's context for the deny: " + c.asMap()));

        // permit_on_first_permit: is at least one of them allowed? The PDP stops at the first
        // permit.
        EvaluationsResponse any = evaluations(client, EvaluationsRequest.builder()
                .subject(user("dave"))
                .action(Action.of("read"))
                .evaluation(EvaluationItem.builder().resource(SALARY_REPORT).build())
                .evaluation(EvaluationItem.builder().resource(HANDBOOK).build())
                .evaluation(EvaluationItem.builder().resource(releaseNotes("published")).build())
                .semantic(EvaluationsSemantic.PERMIT_ON_FIRST_PERMIT)
                .build());
        System.out.printf("  permit_on_first_permit: may dave read any of three documents? (%d of %d answered)%n",
                any.getEvaluations().size(), any.getRequested());
        report("dave reads at least one", true, any.anyPermitted());
    }

    private void clientSubject(String clientId) {
        section("A client as the subject");
        // Keycloak evaluates a client subject only if it is the calling PEP itself
        Subject portal = Subject.of("client", clientId);
        expect("the portal reads the handbook for its search index", true, portal, "read", HANDBOOK);
        expect("the portal updates the handbook", false, portal, "update", HANDBOOK);
    }

    private void asynchronous() {
        section("Asynchronous calls");
        // Fail-closed in a pipeline: a failure becomes a deny instead of a made-up permit
        boolean permitted = client.evaluateAsync(EvaluationRequest.of(user("carol"), Action.of("read"), HANDBOOK))
                .thenApply(EvaluationResponse::getDecision)
                .exceptionally(failure -> false)
                .toCompletableFuture()
                .join();
        report("carol reads the handbook (evaluateAsync)", true, permitted);
    }

    private void failClosed(String pdpUrl, String unreachablePdpUrl) {
        section("Fail-closed: no decision means deny");

        // The PDP rejects the PEP's own credential with 401. That is not a decision about alice.
        AuthZenClient wrongToken = AuthZenClient.builder()
                .pdpUrl(pdpUrl)
                .credentials(CredentialProvider.staticBearer("not-a-valid-token"))
                .build();
        expectNoDecision("alice reads the handbook, but the PDP rejects the PEP's token",
                wrongToken, PdpAuthenticationException.class);

        // An address where no PDP answers; retries are off so that this is quick
        AuthZenClient unreachable = AuthZenClient.builder()
                .pdpUrl(unreachablePdpUrl)
                .connectTimeout(Duration.ofSeconds(1))
                .maxRetries(0)
                .build();
        expectNoDecision("alice reads the handbook, but the PDP is unreachable",
                unreachable, PdpCommunicationException.class);
    }

    /**
     * Asks the PDP and treats every failure as a deny. This is the pattern to use in application
     * code: the client throws an {@link AuthZenException} when it gets no decision.
     */
    private static boolean isPermitted(AuthZenClient client, EvaluationRequest request) {
        try {
            return client.evaluate(request).getDecision();
        } catch (AuthZenException e) {
            LOG.log(Level.WARNING, "No decision from the PDP, denying: {0}", e.toString());
            return false;
        }
    }

    /**
     * The same for a batch: without an answer, every item counts as a deny. A response without
     * decisions does exactly that, since its helpers never treat a missing decision as a permit.
     */
    private static EvaluationsResponse evaluations(AuthZenClient client, EvaluationsRequest request) {
        try {
            return client.evaluations(request);
        } catch (AuthZenException e) {
            LOG.log(Level.WARNING, "No decisions from the PDP, denying all: {0}", e.toString());
            return EvaluationsResponse.of(List.of(), request.getEvaluations().size());
        }
    }

    private void expectNoDecision(String description, AuthZenClient failing,
                                  Class<? extends AuthZenException> expectedFailure) {
        EvaluationRequest request = EvaluationRequest.of(user("alice"), Action.of("read"), HANDBOOK);
        try {
            report(description, false, failing.evaluate(request).getDecision());
            System.out.println("         expected " + expectedFailure.getSimpleName() + ", got a decision");
            unexpected++;
        } catch (AuthZenException e) {
            report(description, false, false);
            System.out.println("         " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (!expectedFailure.isInstance(e)) {
                System.out.println("         expected " + expectedFailure.getSimpleName());
                unexpected++;
            }
        }
    }

    private void expect(String description, boolean expected, Subject subject, String action, Resource resource) {
        expect(description, expected, EvaluationRequest.of(subject, Action.of(action), resource));
    }

    private void expect(String description, boolean expected, EvaluationRequest request) {
        report(description, expected, isPermitted(client, request));
    }

    private void report(String description, boolean expected, boolean actual) {
        String mark = expected == actual ? "" : "   <-- expected " + verdict(expected);
        if (expected != actual) {
            unexpected++;
        }
        System.out.printf("  %-6s %s%s%n", verdict(actual), description, mark);
    }

    private static String verdict(boolean permitted) {
        return permitted ? "permit" : "deny";
    }

    private static Subject user(String username) {
        return Subject.of("user", username);
    }

    private static Resource releaseNotes(String status) {
        return Resource.builder().type("document").id("release-notes").property("status", status).build();
    }

    private static void section(String title) {
        System.out.printf("%n%s%n", title);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            System.err.println("Set the system property " + name + ", for example with -D" + name + "=...");
            System.exit(2);
        }
        return value;
    }
}
