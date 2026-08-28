package com.cde.platform.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One rendering of a JSON object, so that the same content always hashes the
 * same way.
 *
 * <p>Needed because the change summary is stored in a {@code jsonb} column, and
 * {@code jsonb} stores a parsed value rather than the text it was given: it
 * reorders keys and rewrites whitespace. The string that comes back is
 * therefore not the string that went in, so hashing the raw text made every
 * chain verify as broken — the verification was reporting PostgreSQL's
 * normalisation as tampering.
 *
 * <p>{@code json} (rather than {@code jsonb}) would preserve the text exactly
 * and remove the need for this, at the cost of the indexing and containment
 * operators that make the summary queryable. Canonicalising is the cheaper side
 * of that trade: it is a few lines, and it also makes the hash independent of
 * however a future writer happens to order its fields.
 */
final class CanonicalJson {

    private CanonicalJson() {
    }

    /**
     * @return the value re-serialised with object keys in ascending order and
     *         no insignificant whitespace, or the input unchanged when it is
     *         null or cannot be parsed — an unparseable summary is still part
     *         of the record and must still be covered by the hash.
     */
    static String canonicalise(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            return objectMapper.writeValueAsString(sortKeys(objectMapper.readTree(json)));
        } catch (JacksonException e) {
            return json;
        }
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = new ObjectNode(tools.jackson.databind.node
                .JsonNodeFactory.instance);
            List<String> names = new ArrayList<>();
            names.addAll(node.propertyNames());
            names.sort(String::compareTo);
            names.forEach(name -> sorted.set(name, sortKeys(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            // Array order is meaningful, so it is preserved; only the elements
            // are canonicalised.
            var array = node.deepCopy();
            for (int index = 0; index < node.size(); index++) {
                ((tools.jackson.databind.node.ArrayNode) array)
                    .set(index, sortKeys(node.get(index)));
            }
            return array;
        }
        return node;
    }
}
