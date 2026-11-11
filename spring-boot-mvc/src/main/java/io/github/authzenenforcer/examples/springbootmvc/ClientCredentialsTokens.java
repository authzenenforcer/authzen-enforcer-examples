package io.github.authzenenforcer.examples.springbootmvc;

import io.github.authzenenforcer.core.auth.AccessCredential;
import io.github.authzenenforcer.core.auth.CredentialProvider;
import io.github.authzenenforcer.core.spi.JsonCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Obtains the PEP's access token with the OAuth 2.0 client credentials grant and caches it until
 * shortly before it expires.
 *
 * <p>authzen-enforcer never talks to an authorization server; obtaining tokens is the
 * application's job. This class is the one of the plain-java example; a later release of the
 * starter adapts Spring Security's OAuth 2.0 client instead. A provider has to:
 * <ul>
 *   <li>return a cached token quickly, because the client calls it before every request;</li>
 *   <li>let only one caller refresh the token, while the others wait for its result;</li>
 *   <li>drop the token when the PDP rejects it, but only if it is still the current one.</li>
 * </ul>
 */
final class ClientCredentialsTokens implements CredentialProvider {

    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final URI tokenEndpoint;
    private final String authorization;
    private final ReentrantLock refresh = new ReentrantLock();
    private volatile CachedToken current;

    ClientCredentialsTokens(URI tokenEndpoint, String clientId, String clientSecret) {
        this.tokenEndpoint = tokenEndpoint;
        // client_secret_basic (RFC 6749, section 2.3.1): both parts are form-encoded first
        String credentials = formEncode(clientId) + ":" + formEncode(clientSecret);
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public AccessCredential getCredential() {
        CachedToken token = current;
        if (isUsable(token)) {
            return token.credential();
        }
        refresh.lock();
        try {
            // Another caller may have refreshed the token while this one waited
            token = current;
            if (!isUsable(token)) {
                token = fetchToken();
                current = token;
            }
            return token.credential();
        } finally {
            refresh.unlock();
        }
    }

    @Override
    public void invalidate(AccessCredential rejected) {
        refresh.lock();
        try {
            if (current != null && current.credential() == rejected) {
                current = null;
            }
        } finally {
            refresh.unlock();
        }
    }

    private CachedToken fetchToken() {
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Token request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while requesting a token", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token endpoint answered " + response.statusCode());
        }
        // The SDK's built-in JSON codec is enough here, so the example needs no JSON library
        if (!(JsonCodec.defaultCodec().read(response.body()) instanceof Map<?, ?> body)
                || !(body.get("access_token") instanceof String token)
                || !(body.get("expires_in") instanceof Number expiresIn)) {
            throw new IllegalStateException("Token endpoint answered without a usable token");
        }
        return new CachedToken(AccessCredential.bearer(token),
                Instant.now().plusSeconds(expiresIn.longValue()));
    }

    private static boolean isUsable(CachedToken token) {
        return token != null && token.expiresAt().isAfter(Instant.now().plus(EXPIRY_MARGIN));
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record CachedToken(AccessCredential credential, Instant expiresAt) {
    }
}
