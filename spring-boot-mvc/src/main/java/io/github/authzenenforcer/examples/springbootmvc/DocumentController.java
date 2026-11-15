package io.github.authzenenforcer.examples.springbootmvc;

import io.github.authzenenforcer.core.AuthZenException;
import io.github.authzenenforcer.core.client.AuthZenClient;
import io.github.authzenenforcer.core.enforce.SubjectResolver;
import io.github.authzenenforcer.core.model.Action;
import io.github.authzenenforcer.core.model.Context;
import io.github.authzenenforcer.core.model.EvaluationItem;
import io.github.authzenenforcer.core.model.EvaluationsRequest;
import io.github.authzenenforcer.core.model.EvaluationsResponse;
import io.github.authzenenforcer.core.model.Resource;
import io.github.authzenenforcer.spring.CheckPermission;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The documents API: the documents the caller may read, and one document at a time.
 *
 * <p>No handler names an action or a resource: the starter infers them from the HTTP method and the
 * route. {@code GET /documents/handbook} asks whether the caller may {@code read}
 * {@code {"type": "document", "id": "handbook"}}, and {@code GET /documents} whether it may
 * {@code list} {@code {"type": "collection", "id": "document"}}. The subject is the caller of the
 * access token. The handler runs only if the PDP permits the request; {@link #list(Jwt)} then
 * filters the documents.
 */
@RestController
@RequestMapping("/documents")
@CheckPermission
public class DocumentController {

    private final Documents documents;
    private final AuthZenClient client;
    private final SubjectResolver subjects;

    DocumentController(Documents documents, AuthZenClient client, SubjectResolver subjects) {
        this.documents = documents;
        this.client = client;
        this.subjects = subjects;
    }

    /**
     * Lists the documents the caller may read. {@code @CheckPermission} decides whether the caller
     * may list the collection at all; that decision is about the collection, not about the
     * documents in it, so the handler filters them itself. One Access Evaluations request asks for a
     * {@code read} of every document, with the same subject and context as {@link #read(String)},
     * so that the list shows exactly the documents the caller could open. The default semantic,
     * {@code execute_all}, returns a decision for each; one the PDP did not return counts as a deny.
     *
     * <p>A decision per document needs a PDP that can decide about each of them. This realm knows
     * only the documents it was created with, so a document created later, such as bob's
     * {@code document-4}, is never listed. With pagination, filter before paging, or the pages
     * come back short and the totals count documents the caller cannot see.
     */
    @GetMapping
    public List<String> list(@AuthenticationPrincipal Jwt caller) {
        List<String> ids = documents.ids();
        if (ids.isEmpty()) {
            // An empty batch would be a single evaluation of its defaults
            return ids;
        }
        try {
            EvaluationsRequest.Builder request = EvaluationsRequest.builder()
                    .subject(subjects.resolve(caller.getClaims()))
                    .action(Action.of("read"))
                    // The value of the context entry of read()
                    .context(Context.builder().put("mfa", "mfa".equals(caller.getClaimAsString("acr"))).build());
            ids.forEach(id -> request.evaluation(
                    EvaluationItem.builder().resource(Resource.of("document", id)).build()));
            EvaluationsResponse decisions = client.evaluations(request.build());
            return IntStream.range(0, ids.size())
                    .filter(decisions::isPermitted)
                    .mapToObj(ids::get)
                    .toList();
        } catch (AuthZenException e) {
            // No subject or no answer from the PDP: fail closed rather than list nothing
            throw new AccessDeniedException("Denied");
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public String create(@RequestBody String content) {
        return documents.create(content);
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
    public String read(@PathVariable String id) {
        return documents.get(id);
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
    public String download(@PathVariable String id) {
        return documents.get(id);
    }

    @PutMapping("/{id}")
    public void update(@PathVariable String id, @RequestBody String content) {
        documents.update(id, content);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        documents.delete(id);
    }
}
