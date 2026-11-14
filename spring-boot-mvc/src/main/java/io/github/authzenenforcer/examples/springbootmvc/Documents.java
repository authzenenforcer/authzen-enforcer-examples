package io.github.authzenenforcer.examples.springbootmvc;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    List<String> ids() {
        return documents.keySet().stream().sorted().toList();
    }

    String create(String content) {
        String id = "document-" + (documents.size() + 1);
        documents.put(id, content);
        return id;
    }

    /** Returns a document, or answers with {@code 404} if there is none with this id. */
    String get(String id) {
        String content = documents.get(id);
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return content;
    }

    void update(String id, String content) {
        get(id);
        documents.put(id, content);
    }

    void delete(String id) {
        get(id);
        documents.remove(id);
    }

    /** Deletes all documents, or none and answers with {@code 404} if one of them does not exist. */
    void deleteAll(Collection<String> ids) {
        ids.forEach(this::get);
        ids.forEach(documents::remove);
    }
}
