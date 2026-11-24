package io.github.authzenenforcer.examples.springbootwebflux;

import io.netty.channel.ChannelOption;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;

/**
 * The backend of the document portal: a Spring WebFlux application whose endpoints the AuthZEN
 * Enforcer protects. The starter asks the PDP before every handler annotated with
 * {@code @CheckPermission}; see {@link ReactiveDocumentController}.
 */
@SpringBootApplication
public class ReactiveDocumentsApplication {

    public static void main(String[] args) {
        // application.yaml takes the PEP's client secret from this variable. Without it, Spring Boot
        // would send the unresolved placeholder as the secret and every request would be denied
        String secret = System.getenv("DOCUMENT_PORTAL_CLIENT_SECRET");
        if (secret == null || secret.isEmpty()) {
            System.err.println("Set DOCUMENT_PORTAL_CLIENT_SECRET to the secret of the client document-portal");
            System.exit(2);
        }
        SpringApplication application = new SpringApplication(ReactiveDocumentsApplication.class);
        // Before Netty binds any port: Actuator must stay on this machine
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) prepared ->
                publicActuator(prepared.getEnvironment()).ifPresent(reason -> {
                    System.err.println(reason);
                    System.exit(2);
                }));
        application.run(args);
    }

    /**
     * Tells why Actuator, which shows metrics and health details to anyone who reaches it, would be
     * reachable from other machines. Sharing the API's port puts it on the API's interfaces, whatever
     * {@code management.server.address} says; a port of its own needs a loopback address.
     */
    static Optional<String> publicActuator(Environment settings) {
        switch (ManagementPortType.get(settings)) {
            case SAME:
                return Optional.of("management.server.port equals server.port, so Actuator would answer on"
                        + " the API's interfaces; give it a port of its own");
            case DIFFERENT:
                String address = settings.getProperty("management.server.address");
                return loopback(address) ? Optional.empty()
                        : Optional.of("management.server.address " + address + " is not a loopback address;"
                                + " use 127.0.0.1, or Actuator is reachable from other machines");
            default:
                return Optional.empty();
        }
    }

    private static boolean loopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException unresolved) {
            return false;
        }
    }

    /**
     * Writes every span to the log when it ends, so that the traces need no backend here. Spring Boot
     * sends the spans to all exporter beans; an application exports them to its tracing backend
     * instead, for example with management.opentelemetry.tracing.export.otlp.endpoint.
     */
    @Bean
    SpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
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
