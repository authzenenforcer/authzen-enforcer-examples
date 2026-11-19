package io.github.authzenenforcer.examples.springbootwebflux;

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
public class ReactiveBulkDeleteController {

    /** A batch whose size the caller chooses must stay small enough to finish within the timeout. */
    private static final int BATCH_LIMIT = 10;

    private final Documents store;
    private final AuthZenClient pdp;
    private final SubjectResolver subjectResolver;

    ReactiveBulkDeleteController(Documents store, AuthZenClient pdp, SubjectResolver subjectResolver) {
        this.store = store;
        this.pdp = pdp;
        this.subjectResolver = subjectResolver;
    }

    /**
     * Deletes the documents whose ids the body lists, such as {@code ["handbook", "release-notes"]}.
     * Answers with {@code 401} without a token, {@code 403} if the PDP denies one of them or gives no
     * decision, and {@code 404} if one does not exist; in all these cases no document is deleted.
     */
    @PostMapping("/documents/bulk-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDocuments(@AuthenticationPrincipal Jwt caller, @RequestBody Mono<List<String>> body) {
        if (caller == null) {
            // Spring Security's entry point answers with 401
            return Mono.error(new AuthenticationCredentialsNotFoundException("No access token"));
        }
        return body.map(LinkedHashSet::new)
                .filter(documentIds -> !documentIds.isEmpty() && documentIds.size() <= BATCH_LIMIT
                        && !documentIds.contains(null))
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "List between 1 and " + BATCH_LIMIT + " document ids")))
                .flatMap(documentIds -> allDeletable(caller, documentIds)
                        .filter(Boolean::booleanValue)
                        .switchIfEmpty(Mono.error(() -> new AccessDeniedException("Denied")))
                        .doOnNext(permitted -> store.deleteAll(documentIds)))
                .then();
    }

    private Mono<Boolean> allDeletable(Jwt caller, Set<String> documentIds) {
        return Mono.fromCompletionStage(() -> pdp.evaluationsAsync(deleteEach(caller, documentIds)))
                .map(EvaluationsResponse::allPermitted)
                // Whatever fails, from resolving the subject to the PDP's answer: fail closed
                .onErrorReturn(false);
    }

    private EvaluationsRequest deleteEach(Jwt caller, Set<String> documentIds) {
        EvaluationsRequest.Builder batch = EvaluationsRequest.builder()
                .subject(subjectResolver.resolve(caller.getClaims()))
                .action(Action.of("delete"))
                .semantic(EvaluationsSemantic.DENY_ON_FIRST_DENY);
        for (String documentId : documentIds) {
            batch.evaluation(EvaluationItem.builder().resource(Resource.of("document", documentId)).build());
        }
        return batch.build();
    }
}
