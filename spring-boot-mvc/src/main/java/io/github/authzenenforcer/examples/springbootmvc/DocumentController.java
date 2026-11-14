package io.github.authzenenforcer.examples.springbootmvc;

import io.github.authzenenforcer.spring.CheckPermission;

import org.springframework.http.HttpStatus;
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

/**
 * The documents API, one document at a time.
 *
 * <p>No handler names an action or a resource: the starter infers them from the HTTP method and the
 * route. {@code GET /documents/handbook} asks whether the caller may {@code read}
 * {@code {"type": "document", "id": "handbook"}}, and {@code GET /documents} whether it may
 * {@code list} {@code {"type": "collection", "id": "document"}}. The subject is the caller of the
 * access token. The handler runs only if the PDP permits the request.
 */
@RestController
@RequestMapping("/documents")
@CheckPermission
public class DocumentController {

    private final Documents documents;

    DocumentController(Documents documents) {
        this.documents = documents;
    }

    @GetMapping
    public List<String> list() {
        return documents.ids();
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
