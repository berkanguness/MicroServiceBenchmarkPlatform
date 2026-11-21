package com.benchmark.lab_result_service.testsupport;

import java.util.Iterator;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The sample GraphQL operations of contract §7.2, verbatim (QA uses S1/S2/S2b as they are), plus the normalization
 * that turns a GraphQL {@code data} tree into the REST JSON tree: GraphQL {@code ID} is a JSON string (D11), so every
 * {@code id} member becomes a number; everything else is kept as it is.
 */
public final class GraphQlDocuments {

    public static final String S1 = """
            query S1($id: ID!) {
              patientSummary(patientId: $id) {
                patient { id tcNo fullName birthDate bloodType }
                latestReport { id doctorName sampleType createdAt
                  items { id testCode testName resultValue unit isCritical } }
              }
            }""";

    public static final String S2 = """
            query S2($id: ID!) {
              patientLabHistory(patientId: $id) {
                patient { id tcNo fullName birthDate bloodType }
                reports { id doctorName sampleType createdAt
                  items { id testCode testName resultValue unit isCritical } }
              }
            }""";

    public static final String S2B = """
            query S2b($id: ID!) {
              patientLabHistory(patientId: $id) { reports { items { testName resultValue } } }
            }""";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static final Map<String, String> BY_NAME = Map.of("S1", S1, "S2", S2, "S2b", S2B);

    private GraphQlDocuments() {}

    /** {@code {"query": …, "operationName": …, "variables": {"id": id}}} as QA's k6 client sends it. */
    public static String body(String operationName, String id) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("query", BY_NAME.get(operationName));
        body.put("operationName", operationName);
        body.putObject("variables").put("id", id);
        return body.toString();
    }

    /**
     * A copy of {@code node} with every {@code id} member converted from a GraphQL ID string to a JSON number. The
     * copy is re-parsed, so its number nodes have the types a parser gives them (int vs long), as in a parsed REST body.
     */
    public static JsonNode idsToNumbers(JsonNode node) {
        return JSON.readTree(convert(node).toString());
    }

    private static JsonNode convert(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = object.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode value = e.getValue();
                if (e.getKey().equals("id") && value.isString()) {
                    copy.put("id", Long.parseLong(value.asString()));
                } else {
                    copy.set(e.getKey(), convert(value));
                }
            }
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            array.forEach(element -> copy.add(convert(element)));
            return copy;
        }
        return node;
    }
}
