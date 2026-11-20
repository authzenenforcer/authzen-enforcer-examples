package io.github.authzenenforcer.examples.springbootwebflux;

import io.github.authzenenforcer.micrometer.AuthZenClientMetrics;
import io.github.authzenenforcer.micrometer.EnforcerMetrics;
import io.github.authzenenforcer.micrometer.MicrometerPepListener;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calls the application's own endpoints as the users of the realm once it has started, prints the
 * status of every call, and exits: with status 1 if a status differs from the expected one, so that
 * it doubles as a check in CI. Runs only with {@code --example.check=true}.
 *
 * <p>The users sign in with the password grant of the public client {@code documents-cli}, which
 * the realm has for this check only. A real client would use the authorization code flow.
 */
@Component
@ConditionalOnProperty(name = "example.check", havingValue = "true")
class DecisionCheck implements ApplicationRunner {

    // Listeners get the events on a thread of their own, shortly after the calls
    private static final Duration EVENT_WAIT = Duration.ofSeconds(10);

    private final ConfigurableApplicationContext context;
    private final Environment environment;
    private final MeterRegistry registry;
    private final RestClient tokens;
    private final String userClientId;
    private final String clientId;
    private final Map<String, String> userTokens = new HashMap<>();

    DecisionCheck(ConfigurableApplicationContext context, Environment environment, MeterRegistry registry,
            @Value("${example.token-url}") String tokenUrl, @Value("${example.user-client-id}") String userClientId,
            @Value("${spring.security.oauth2.client.registration.pdp.client-id}") String clientId) {
        this.context = context;
        this.environment = environment;
        this.registry = registry;
        this.tokens = RestClient.create(tokenUrl);
        this.userClientId = userClientId;
        this.clientId = clientId;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String password = System.getenv("EXAMPLE_USER_PASSWORD");
        if (password == null || password.isEmpty()) {
            System.err.println("Set EXAMPLE_USER_PASSWORD to the password of the realm's users");
            exit(2);
            return;
        }
        // Known once the web server has started
        RestClient api = RestClient.create("http://127.0.0.1:" + environment.getProperty("local.server.port"));
        int unexpected = 0;
        System.out.println();
        System.out.println("A document");
        unexpected += expect(api, "bob reads the handbook", 200, "bob", HttpMethod.GET, "/documents/handbook", password);
        unexpected += expect(api, "erin, who has no role, reads the handbook", 403, "erin", HttpMethod.GET,
                "/documents/handbook", password);
        unexpected += expect(api, "an anonymous caller reads the handbook", 401, null, HttpMethod.GET,
                "/documents/handbook", password);
        unexpected += expect(api, "bob downloads the handbook (explicit values)", 200, "bob", HttpMethod.GET,
                "/documents/handbook/download", password);
        unexpected += expect(api, "erin downloads the handbook (explicit values)", 403, "erin", HttpMethod.GET,
                "/documents/handbook/download", password);
        unexpected += expect(api, "bob updates the handbook", 200, "bob", HttpMethod.PUT, "/documents/handbook", password);
        unexpected += expect(api, "bob deletes the handbook", 403, "bob", HttpMethod.DELETE, "/documents/handbook",
                password);
        unexpected += expect(api, "dave (hr) reads the salary report without MFA", 403, "dave", HttpMethod.GET,
                "/documents/salary-report", password);
        // The realm issued this token to the PEP for the PDP, not for this API
        unexpected += expectStatus(api.get().uri("/documents/handbook").header("Authorization", "Bearer " + pepToken()),
                "the PEP's own token reads the handbook", 401);
        System.out.println();
        System.out.println("The document collection");
        // Nobody may read the salary report without MFA, so it is not listed
        unexpected += expectList(api, "carol lists the documents", "carol", List.of("handbook", "release-notes"),
                password);
        unexpected += expect(api, "erin, who has no role, lists the documents", 403, "erin", HttpMethod.GET,
                "/documents", password);
        unexpected += expect(api, "carol creates a document", 403, "carol", HttpMethod.POST, "/documents", password);
        unexpected += expect(api, "bob creates a document", 201, "bob", HttpMethod.POST, "/documents", password);
        // bob's document-4 is unknown to the realm, so the PDP never permits reading it
        unexpected += expectList(api, "bob lists the documents, without his new one", "bob",
                List.of("handbook", "release-notes"), password);
        unexpected += expect(api, "alice deletes the handbook", 204, "alice", HttpMethod.DELETE, "/documents/handbook",
                password);
        System.out.println();
        System.out.println("Several documents at once");
        unexpected += expectBulkDelete(api, "bob deletes the release notes and the salary report", 403, "bob",
                List.of("release-notes", "salary-report"), password);
        // bob's document-4 is unknown to the realm, so it is denied, and the release notes stay
        unexpected += expectBulkDelete(api, "alice deletes the release notes and bob's new document", 403, "alice",
                List.of("release-notes", "document-4"), password);
        unexpected += expectBulkDelete(api, "alice deletes the release notes and the salary report", 204, "alice",
                List.of("release-notes", "salary-report"), password);
        System.out.println();
        System.out.println("Actuator");
        // Known once the management server has started; a port of its own, on 127.0.0.1
        RestClient actuator = RestClient.create("http://127.0.0.1:" + environment.getProperty("local.management.port"));
        unexpected += expectStatus(actuator.get().uri("/actuator/health/authzen"), "the authzen health indicator", 200);
        unexpected += expectStatus(actuator.get().uri("/actuator/metrics/" + MicrometerPepListener.ENFORCEMENTS),
                "the meter " + MicrometerPepListener.ENFORCEMENTS, 200);
        unexpected += expectStatus(api.get().uri("/actuator/health"), "Actuator on the port of the API", 404);
        System.out.println();
        System.out.println("Metrics");
        unexpected += expectMeters();
        System.out.println();
        if (unexpected > 0) {
            System.out.printf("%d status(es) or meter(s) differ from the expected ones.%n", unexpected);
            exit(1);
            return;
        }
        System.out.println("All statuses and meters are as expected.");
        exit(0);
    }

    /**
     * Prints the meters of the requests above and compares them with those they should have
     * recorded. The numbers of enforcements and calls are exact: every run makes the same requests.
     * Attempts and dropped events only need to be there.
     */
    private int expectMeters() {
        long deadline = System.nanoTime() + EVENT_WAIT.toNanos();
        List<String> missing = missingMeters();
        while (!missing.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            missing = missingMeters();
        }
        Map<String, Long> lines = new TreeMap<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (name.equals(MicrometerPepListener.ENFORCEMENTS) || name.equals(MicrometerPepListener.CALLS)) {
                lines.merge(describe(meter), value(meter), Long::sum);
            }
        }
        lines.forEach((meter, value) -> System.out.printf("  %-3d %s%n", value, meter));
        for (String problem : missing) {
            System.out.println("  <-- expected " + problem);
        }
        return missing.size();
    }

    private List<String> missingMeters() {
        List<String> missing = new ArrayList<>();
        // One enforcement per request that reached a handler: the 200s, 201s and 204s are permitted, the
        // 403s of the PDP denied, the anonymous call unauthenticated. The PEP's own token is rejected
        // before, by Spring Security
        expectCount(missing, 7, MicrometerPepListener.ENFORCEMENTS, "outcome", "permitted");
        expectCount(missing, 6, MicrometerPepListener.ENFORCEMENTS, "outcome", "denied");
        expectCount(missing, 1, MicrometerPepListener.ENFORCEMENTS, "outcome", "unauthenticated");
        // A call to the PDP for every enforcement with a subject, and an Access Evaluations request for
        // each of the two lists and the three bulk deletes
        expectCount(missing, 7, MicrometerPepListener.CALLS, "operation", "evaluation", "outcome", "permit");
        expectCount(missing, 6, MicrometerPepListener.CALLS, "operation", "evaluation", "outcome", "deny");
        expectCount(missing, 5, MicrometerPepListener.CALLS, "operation", "evaluations", "outcome", "completed");
        expectSome(missing, MicrometerPepListener.ATTEMPTS);
        expectSome(missing, AuthZenClientMetrics.LISTENER_DROPPED);
        expectSome(missing, EnforcerMetrics.LISTENER_DROPPED);
        return missing;
    }

    private void expectCount(List<String> missing, long expected, String name, String... tags) {
        long actual = registry.find(name).tags(tags).meters().stream().mapToLong(DecisionCheck::value).sum();
        if (actual != expected) {
            missing.add(expected + " " + name + tagText(tags) + ", got " + actual);
        }
    }

    private void expectSome(List<String> missing, String name) {
        if (registry.find(name).meters().isEmpty()) {
            missing.add(name);
        }
    }

    private static String tagText(String... tags) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < tags.length; i += 2) {
            text.append(' ').append(tags[i]).append('=').append(tags[i + 1]);
        }
        return text.toString();
    }

    private static String describe(Meter meter) {
        StringBuilder text = new StringBuilder(meter.getId().getName());
        meter.getId().getTags().forEach(tag -> text.append(' ').append(tag.getKey()).append('=').append(tag.getValue()));
        return text.toString();
    }

    // The count of a timer or counter
    private static long value(Meter meter) {
        return (long) meter.measure().iterator().next().getValue();
    }

    private int expect(RestClient api, String description, int expected, String user, HttpMethod method, String path,
            String password) {
        RestClient.RequestBodySpec request = api.method(method).uri(path);
        if (method == HttpMethod.POST || method == HttpMethod.PUT) {
            request.contentType(MediaType.TEXT_PLAIN).body("Written by " + user);
        }
        return expect(request, description, expected, user, password);
    }

    /** Lists the documents as the user and compares the listed ids, in order, with the expected ones. */
    private int expectList(RestClient api, String description, String user, List<String> expected, String password) {
        Listed listed = api.get().uri("/documents")
                .header("Authorization", "Bearer " + token(user, password))
                .exchange((sent, response) -> {
                    int status = response.getStatusCode().value();
                    return new Listed(status, status == 200 ? response.bodyTo(List.class) : null);
                });
        boolean asExpected = listed.status() == 200 && expected.equals(listed.ids());
        String mark = asExpected ? "" : "   <-- expected 200 " + expected;
        System.out.printf("  %d %s: %s%s%n", listed.status(), description, listed.ids(), mark);
        return asExpected ? 0 : 1;
    }

    private record Listed(int status, List<?> ids) {
    }

    private int expectBulkDelete(RestClient api, String description, int expected, String user, List<String> ids,
            String password) {
        RestClient.RequestBodySpec request = api.post().uri("/documents/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ids);
        return expect(request, description, expected, user, password);
    }

    private int expect(RestClient.RequestBodySpec request, String description, int expected, String user,
            String password) {
        if (user != null) {
            request.header("Authorization", "Bearer " + token(user, password));
        }
        return expectStatus(request, description, expected);
    }

    private int expectStatus(RestClient.RequestHeadersSpec<?> request, String description, int expected) {
        int status = request.exchange((sent, response) -> response.getStatusCode().value());
        String mark = status == expected ? "" : "   <-- expected " + expected;
        System.out.printf("  %d %s%s%n", status, description, mark);
        return status == expected ? 0 : 1;
    }

    private String token(String user, String password) {
        return userTokens.computeIfAbsent(user, name -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "password");
            form.add("client_id", userClientId);
            form.add("username", name);
            form.add("password", password);
            return accessToken(form, name);
        });
    }

    /** The PEP's own token, from the client credentials grant, as the PEP obtains it for the PDP. */
    private String pepToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", System.getenv("DOCUMENT_PORTAL_CLIENT_SECRET"));
        return accessToken(form, clientId);
    }

    private String accessToken(MultiValueMap<String, String> form, String name) {
        Map<?, ?> response = tokens.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String token)) {
            throw new IllegalStateException("The token endpoint answered without a token for " + name);
        }
        return token;
    }

    private void exit(int status) {
        System.exit(SpringApplication.exit(context, () -> status));
    }
}
