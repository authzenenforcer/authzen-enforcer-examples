package io.github.authzenenforcer.examples.springbootmvc;

import io.github.authzenenforcer.core.auth.CredentialProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;

/**
 * The backend of the document portal: a Spring MVC application whose endpoints the AuthZEN
 * Enforcer protects. The starter asks the PDP before every handler annotated with
 * {@code @CheckPermission}; see {@link DocumentController}.
 */
@SpringBootApplication
public class DocumentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentsApplication.class, args);
    }

    /**
     * How the PEP authenticates to the PDP: with its own access token, from the client credentials
     * grant. The starter never forwards the caller's token to the PDP.
     */
    @Bean
    CredentialProvider pdpCredentials(@Value("${example.token-url}") URI tokenUrl,
            @Value("${example.client-id}") String clientId) {
        // From the environment, never from a file or the command line
        String secret = System.getenv("DOCUMENT_PORTAL_CLIENT_SECRET");
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException("Set DOCUMENT_PORTAL_CLIENT_SECRET to the secret of the client "
                    + clientId);
        }
        return new ClientCredentialsTokens(tokenUrl, clientId, secret);
    }

    /**
     * Spring Security authenticates callers by their access token. It lets every request reach the
     * handlers: the PDP decides what a caller may do, and the starter answers callers without a
     * token with 401 through Spring Security's entry point.
     */
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // A browser does not add an Authorization header to a cross-site request by itself, so
                // requests that carry one need no CSRF token; any other request still does. Requiring one
                // would deny the bearer-token writes of this API before the PDP is asked
                // NOSONAR on the next line: java:S4502, safe for the reason above
                .csrf(csrf -> csrf.ignoringRequestMatchers(request -> request.getHeader("Authorization") != null)) // NOSONAR
                .build();
    }
}
