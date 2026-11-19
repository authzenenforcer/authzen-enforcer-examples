package io.github.authzenenforcer.examples.springbootwebflux;

import io.netty.channel.ChannelOption;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * The backend of the document portal: a Spring WebFlux application whose endpoints the AuthZEN
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
            System.err.println("Set DOCUMENT_PORTAL_CLIENT_SECRET to the secret of the client document-portal");
            System.exit(2);
        }
        SpringApplication.run(DocumentsApplication.class, args);
    }

    /**
     * How the PEP obtains its own access token for the PDP: the starter uses Spring Security's
     * reactive OAuth 2.0 client with the registration of {@code authzen.pdp.client-registration-id},
     * and never forwards the caller's token. This bean only adds timeouts to the token requests,
     * which Spring Security's default client does not set. The starter also cancels a token request
     * that takes longer than {@code authzen.pdp.request-timeout}.
     */
    @Bean
    ReactiveOAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenResponseClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(5));
        WebClientReactiveClientCredentialsTokenResponseClient client =
                new WebClientReactiveClientCredentialsTokenResponseClient();
        client.setWebClient(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build());
        return client;
    }

    /**
     * Spring Security authenticates callers by their access token. It lets every request reach the
     * handlers: the PDP decides what a caller may do, and the starter answers callers without a
     * token with 401 through Spring Security's entry point.
     */
    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        // A browser does not add an Authorization header to a cross-site request by itself, so
        // requests that carry one need no CSRF token; any other request still does. Requiring one
        // would deny the bearer-token writes of this API before the PDP is asked
        ServerWebExchangeMatcher withAuthorization = exchange ->
                exchange.getRequest().getHeaders().containsHeader("Authorization")
                        ? ServerWebExchangeMatcher.MatchResult.match()
                        : ServerWebExchangeMatcher.MatchResult.notMatch();
        return http
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                // NOSONAR on the next line: java:S4502, safe for the reason above
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(new AndServerWebExchangeMatcher( // NOSONAR
                        CsrfWebFilter.DEFAULT_CSRF_MATCHER, new NegatedServerWebExchangeMatcher(withAuthorization))))
                .build();
    }
}
