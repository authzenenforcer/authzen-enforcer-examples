package io.github.authzenenforcer.examples.springbootwebflux;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.EvaluationsSemantic;
import io.github.authzenenforcer.core.model.Resource;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deletes several documents at once: all of them, or none.
 *
 * <p>{@code @CheckPermission} asks the PDP about one resource, so this handler has none and asks
 * itself, with the starter's {@link AuthZenClient}. One Access Evaluations request carries a
 * {@code delete} for every document, with {@code deny_on_first_deny}: the PDP stops at the first
 * document the caller may not delete, since the operation would not run anyway. A decision the PDP
 * did not return counts as a deny, so {@code allPermitted()} is true only if every document was
 * permitted. The subject comes from the starter's {@link SubjectResolver}, the same one that
 * {@code @CheckPermission} uses. The request goes out with the client's asynchronous method, so no
 * thread waits for the PDP.
 */
@RestController
public class BulkDeleteController {

    /** A batch whose size the caller chooses must stay small enough to finish within the timeout. */
    private static final int MAX_DOCUMENTS = 10;

    private final Documents documents;
    private final AuthZenClient client;
    private final SubjectResolver subjects;

    BulkDeleteController(Documents documents, AuthZenClient client, SubjectResolver subjects) {
        this.documents = documents;
        this.client = client;
        this.subjects = subjects;
    }

    /**
     * Deletes the documents whose ids the body lists, such as {@code ["handbook", "release-notes"]}.
     * Answers with {@code 401} without a token, {@code 403} if the PDP denies one of them or gives no
     * decision, and {@code 404} if one does not exist; in all these cases no document is deleted.
     */
    @PostMapping("/documents/bulk-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> bulkDelete(@AuthenticationPrincipal Jwt caller, @RequestBody List<String> ids) {
        if (caller == null) {
            // Spring Security's entry point answers with 401
            return Mono.error(new AuthenticationCredentialsNotFoundException("No access token"));
        }
        Set<String> unique = new LinkedHashSet<>(ids);
        if (unique.isEmpty() || unique.size() > MAX_DOCUMENTS || unique.contains(null)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "List between 1 and " + MAX_DOCUMENTS + " document ids"));
        }
        return permitted(caller, unique)
                .flatMap(permitted -> {
                    if (!permitted) {
                        return Mono.error(new AccessDeniedException("Denied"));
                    }
                    documents.deleteAll(unique);
                    return Mono.<Void>empty();
                });
    }

    private Mono<Boolean> permitted(Jwt caller, Set<String> ids) {
        return Mono.defer(() -> {
                    EvaluationsRequest.Builder request = EvaluationsRequest.builder()
                            .subject(subjects.resolve(caller.getClaims()))
                            .action(Action.of("delete"))
                            .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY);
                    ids.forEach(id -> request.evaluation(
                            EvaluationItem.builder().resource(Resource.of("document", id)).build()));
                    return Mono.fromCompletionStage(() -> client.evaluationsAsync(request.build()));
                })
                .map(EvaluationsResponse::allPermitted)
                // No subject or no answer from the PDP: fail closed
                .onErrorReturn(AuthZenException.class, false);
    }
}
