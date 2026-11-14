package io.github.authzenenforcer.examples.springbootmvc;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final ConfigurableApplicationContext context;
    private final Environment environment;
    private final RestClient tokens;
    private final String userClientId;
    private final Map<String, String> userTokens = new HashMap<>();

    DecisionCheck(ConfigurableApplicationContext context, Environment environment,
            @Value("${example.token-url}") String tokenUrl, @Value("${example.user-client-id}") String userClientId) {
        this.context = context;
        this.environment = environment;
        this.tokens = RestClient.create(tokenUrl);
        this.userClientId = userClientId;
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
        System.out.println();
        System.out.println("The document collection");
        unexpected += expect(api, "carol lists the documents", 200, "carol", HttpMethod.GET, "/documents", password);
        unexpected += expect(api, "carol creates a document", 403, "carol", HttpMethod.POST, "/documents", password);
        unexpected += expect(api, "bob creates a document", 201, "bob", HttpMethod.POST, "/documents", password);
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
        if (unexpected > 0) {
            System.out.printf("%d status(es) differ from the expected ones.%n", unexpected);
            exit(1);
            return;
        }
        System.out.println("All statuses are as expected.");
        exit(0);
    }

    private int expect(RestClient api, String description, int expected, String user, HttpMethod method, String path,
            String password) {
        RestClient.RequestBodySpec request = api.method(method).uri(path);
        if (method == HttpMethod.POST || method == HttpMethod.PUT) {
            request.contentType(MediaType.TEXT_PLAIN).body("Written by " + user);
        }
        return expect(request, description, expected, user, password);
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
            Map<?, ?> response = tokens.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("access_token") instanceof String token)) {
                throw new IllegalStateException("The token endpoint answered without a token for " + name);
            }
            return token;
        });
    }

    private void exit(int status) {
        System.exit(SpringApplication.exit(context, () -> status));
    }
}
