package io.github.authzenenforcer.examples.springbootwebflux;

import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.spring.CallerSubjects;
import io.github.authzenenforcer.spring.CheckPermission;
import io.github.authzenenforcer.spring.reactive.ReactiveAuthZenClient;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The documents API: the documents the caller may read, and one document at a time.
 *
 * <p>No handler names an action or a resource: the starter infers them from the HTTP method and the
 * route. {@code GET /documents/handbook} asks whether the caller may {@code read}
 * {@code {"type": "document", "id": "handbook"}}, and {@code GET /documents} whether it may
 * {@code list} {@code {"type": "collection", "id": "document"}}. The subject is the caller of the
 * access token. The handler runs only if the PDP permits the request;
 * {@link #readableDocuments(Authentication)} then filters the documents.
 *
 * <p>The starter decides before the handler's arguments are resolved, so the body of a request that
 * the PDP denies is never read. The decision starts on Reactor's bounded elastic scheduler and does
 * not block the event loop.
 */
@RestController
@RequestMapping("/documents")
@CheckPermission
public class ReactiveDocumentController {

    private final Documents store;
    private final ReactiveAuthZenClient pdp;
    private final CallerSubjects callerSubjects;

    ReactiveDocumentController(Documents store, ReactiveAuthZenClient pdp, CallerSubjects callerSubjects) {
        this.store = store;
        this.pdp = pdp;
        this.callerSubjects = callerSubjects;
    }

    /**
     * Lists the documents the caller may read. {@code @CheckPermission} decides whether the caller
     * may list the collection at all; that decision is about the collection, not about the
     * documents in it, so the handler filters them itself. One Access Evaluations request asks for a
     * {@code read} of every document, with the same subject and context as
     * {@link #readDocument(String)}, so that the list shows exactly the documents the caller could
     * open. The default semantic, {@code execute_all}, returns a decision for each; the client
     * rejects an answer that lacks one, and that fails the request like any other failure of the PDP.
     *
     * <p>The request goes out with the starter's {@link ReactiveAuthZenClient}, so no thread waits
     * for the PDP, cancelling the response cancels it, and its span joins the trace of the request.
     *
     * <p>A decision per document needs a PDP that can decide about each of them. This realm knows
     * only the documents it was created with, so a document created later, such as bob's
     * {@code document-4}, is never listed. With pagination, filter before paging, or the pages
     * come back short and the totals count documents the caller cannot see.
     */
    @GetMapping
    public Mono<List<String>> readableDocuments(Authentication caller) {
        if (!(caller instanceof JwtAuthenticationToken token)) {
            // Reached only if the starter lets anonymous callers through and the PDP permits them to
            // list; documents are read with the caller's claims, so answer 401
            return Mono.error(new AuthenticationCredentialsNotFoundException("No access token"));
        }
        List<String> documentIds = store.ids();
        if (documentIds.isEmpty()) {
            // An empty batch would be a single evaluation of its defaults
            return Mono.just(documentIds);
        }
        // Deferred, so that a failure to build the request fails the subscription like one of the PDP
        return Mono.defer(() -> pdp.evaluations(readEach(token, documentIds)))
                .map(decisions -> permitted(documentIds, decisions))
                // Whatever fails, from resolving the subject to the PDP's answer: fail closed rather
                // than list nothing
                .onErrorMap(failure -> new AccessDeniedException("Denied"));
    }

    /** One {@code read} per document, with the subject and the context entry of readDocument(). */
    private EvaluationsRequest readEach(JwtAuthenticationToken caller, List<String> documentIds) {
        boolean secondFactor = "mfa".equals(caller.getToken().getClaimAsString("acr"));
        EvaluationsRequest.Builder batch = EvaluationsRequest.builder()
                .subject(callerSubjects.of(caller).orElseThrow(() -> new AccessDeniedException("No subject")))
                .action(Action.of("read"))
                .context(Context.builder().put("mfa", secondFactor).build());
        for (String documentId : documentIds) {
            batch.evaluation(EvaluationItem.builder().resource(Resource.of("document", documentId)).build());
        }
        return batch.build();
    }

    private static List<String> permitted(List<String> documentIds, EvaluationsResponse decisions) {
        return IntStream.range(0, documentIds.size())
                .filter(decisions::isPermitted)
                .mapToObj(documentIds::get)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createDocument(@RequestBody Mono<String> content) {
        return content.map(store::create);
    }

    /**
     * Reads a document. The salary report needs a sign-in with a second factor, which the realm
     * checks through {@code context.mfa}. The context entry derives that value from the {@code acr}
     * claim of the access token; the users of this realm have no second factor, so it is false.
     * Expressions may read only path variables, a few values of the request and the caller's
     * claims, never the caller's token. {@code #claims} is null for a caller without a token, hence
     * the null-safe index.
     */
    @CheckPermission(context = "mfa=#claims?.['acr'] == 'mfa'")
    @GetMapping("/{id}")
    public Mono<String> readDocument(@PathVariable String id) {
        return Mono.fromSupplier(() -> store.get(id));
    }

    /**
     * Downloads a document. Inferred, {@code GET /documents/{id}/download} would be listing a nested
     * collection: {@code list} on {@code {"type": "collection", "id": "download"}}, with the document
     * as its parent. The values given here win over inference: the action is {@code read} and the
     * resource is the document itself, so the realm's {@code read} rule applies. An explicit action
     * alone would not do: for a {@code GET} it changes the action, not the resource. The class
     * annotation still applies; this one adds to it.
     */
    @CheckPermission(action = "read", resourceType = "document", resourceId = "#id")
    @GetMapping("/{id}/download")
    public Mono<String> downloadDocument(@PathVariable String id) {
        return readDocument(id);
    }

    @PutMapping("/{id}")
    public Mono<Void> replaceDocument(@PathVariable String id, @RequestBody Mono<String> content) {
        return content.doOnNext(text -> store.update(id, text)).then();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDocument(@PathVariable String id) {
        return Mono.fromRunnable(() -> store.delete(id));
    }
}
