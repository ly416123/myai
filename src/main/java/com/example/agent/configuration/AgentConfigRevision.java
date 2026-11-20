package com.example.agent.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** A content-addressed immutable configuration revision. */
public record AgentConfigRevision(String revisionId, String contentHash, AgentConfigDocument document) {
    public AgentConfigRevision {
        if (revisionId == null || contentHash == null || document == null) {
            throw new IllegalArgumentException("Revision ID, hash and document are required");
        }
        if (!contentHash.matches("[a-f0-9]{64}") || !revisionId.equals("rev-" + contentHash)) {
            throw new IllegalArgumentException("Configuration revision identity is invalid");
        }
    }

    public static AgentConfigRevision create(AgentConfigDocument document, ObjectMapper mapper) {
        if (document == null || mapper == null) throw new IllegalArgumentException("Document and mapper are required");
        try {
            JsonNode tree = canonicalize(mapper.valueToTree(document));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(tree)));
            return new AgentConfigRevision("rev-" + hash, hash, document);
        } catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("Unable to calculate configuration revision hash", failure);
        }
    }

    private static JsonNode canonicalize(JsonNode source) {
        if (source.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            source.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> result.set(name, canonicalize(value)));
            return result;
        }
        if (source.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            source.elements().forEachRemaining(value -> result.add(canonicalize(value)));
            return result;
        }
        if (source.isNumber()) {
            return JsonNodeFactory.instance.numberNode(source.decimalValue().stripTrailingZeros());
        }
        return source.deepCopy();
    }
}
