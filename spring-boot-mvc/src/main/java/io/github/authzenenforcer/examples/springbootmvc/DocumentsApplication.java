package io.github.authzenenforcer.examples.springbootmvc;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * The backend of the document portal: a Spring MVC application whose endpoints the AuthZEN
 * Enforcer protects. The starter asks the PDP before every handler annotated with
 * {@code @CheckPermission}; see {@link DocumentController}.
 */
@SpringBootApplication
public class DocumentsApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DocumentsApplication.class);
        // Checked once the settings and the profiles are known, before any server starts
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            String problem = missingSecret(event.getEnvironment());
            if (problem == null) {
                problem = actuatorExposure(event.getEnvironment());
            }
            if (problem != null) {
                System.err.println(problem);
                System.exit(2);
            }
        });
        application.run(args);
    }

    /**
     * Why the PEP could not authenticate to the PDP, or null if it can. With a client registration
     * for the PDP, as in the keycloak profile, its client secret comes from this variable. Without
     * it, Spring Boot would send the unresolved placeholder as the secret and every request would be
     * denied.
     */
    static String missingSecret(Environment environment) {
        String secret = System.getenv("DOCUMENT_PORTAL_CLIENT_SECRET");
        if (environment.containsProperty("authzen.pdp.client-registration-id") && (secret == null || secret.isEmpty())) {
            return "Set DOCUMENT_PORTAL_CLIENT_SECRET to the secret of the client document-portal";
        }
        return null;
    }

    /**
     * Why Actuator would answer beyond this machine, or null if it does not. Actuator shows metrics
     * and health details without authentication, so it must listen on a loopback address. On the
     * port of the API, Spring Boot serves it on every interface of the API and ignores
     * {@code management.server.address}.
     */
    static String actuatorExposure(Environment environment) {
        ManagementPortType type = ManagementPortType.get(environment);
        if (type == ManagementPortType.SAME) {
            return "Set management.server.port to another port than server.port; on the same port, Actuator"
                    + " answers on every interface of the API";
        }
        if (type == ManagementPortType.DIFFERENT && !isLoopback(environment.getProperty("management.server.address"))) {
            return "Set management.server.address to a loopback address such as 127.0.0.1; Actuator shows"
                    + " metrics and health details without authentication";
        }
        return null;
    }

    private static boolean isLoopback(String address) {
        if (address == null) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
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
