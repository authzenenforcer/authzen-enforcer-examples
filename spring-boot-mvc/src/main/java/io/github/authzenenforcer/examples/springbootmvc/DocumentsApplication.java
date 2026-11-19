package io.github.authzenenforcer.examples.springbootmvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The backend of the document portal: a Spring MVC application whose endpoints the AuthZEN
 * Enforcer protects. The starter asks the PDP before every handler annotated with
 * {@code @CheckPermission}; see {@link DocumentController}.
 */
@SpringBootApplication
public class DocumentsApplication {

    public static void main(String[] args) {
        // application.yaml takes the PEP's client secret from this variable. Without it, Spring Boot
        // would send the unresolved placeholder as the secret and every request would be denied
        String secret = System.getenv("DOCUMENT_PORTAL_CLIENT_SECRET");
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException(
                    "Set DOCUMENT_PORTAL_CLIENT_SECRET to the secret of the client document-portal");
        }
        SpringApplication.run(DocumentsApplication.class, args);
    }

    /**
     * How the PEP obtains its own access token for the PDP: the starter uses Spring Security's
     * OAuth 2.0 client with the registration of {@code authzen.pdp.client-registration-id}, and never
     * forwards the caller's token. This bean only adds timeouts to the token requests, which Spring
     * Security's default client does not set; the converters and the error handler are its defaults.
     */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenResponseClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientClientCredentialsTokenResponseClient client = new RestClientClientCredentialsTokenResponseClient();
        client.setRestClient(restClient);
        return client;
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
