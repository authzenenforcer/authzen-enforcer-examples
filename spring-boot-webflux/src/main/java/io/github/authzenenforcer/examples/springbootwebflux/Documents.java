package io.github.authzenenforcer.examples.springbootwebflux;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The documents of the portal, kept in memory and shared by {@link DocumentController} and
 * {@link BulkDeleteController}.
 */
@Component
class Documents {

    private final Map<String, String> documents = new ConcurrentHashMap<>(Map.of(
            "handbook", "How we work",
            "release-notes", "What changed",
            "salary-report", "Who earns what"));

    /** The number of the last created document; never reused, and a new document replaces none. */
    private final AtomicInteger created = new AtomicInteger(documents.size());

    List<String> ids() {
        return documents.keySet().stream().sorted().toList();
    }

    String create(String content) {
        String id;
        do {
            id = "document-" + created.incrementAndGet();
        } while (documents.putIfAbsent(id, content) != null);
        return id;
    }

    /** Returns a document, or answers with {@code 404} if there is none with this id. */
    String get(String id) {
        String content = documents.get(id);
        if (content == null) {
            throw notFound();
        }
        return content;
    }

    /** Replaces a document in one step, so that it cannot bring back one deleted meanwhile. */
    void update(String id, String content) {
        if (documents.computeIfPresent(id, (key, old) -> content) == null) {
            throw notFound();
        }
    }

    void delete(String id) {
        if (documents.remove(id) == null) {
            throw notFound();
        }
    }

    /** Deletes all documents, or none and answers with {@code 404} if one of them does not exist. */
    void deleteAll(Collection<String> ids) {
        ids.forEach(this::get);
        ids.forEach(documents::remove);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
