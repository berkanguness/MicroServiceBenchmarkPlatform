package com.benchmark.lab_result_service.testsupport;

import com.benchmark.lab_result_service.core.api.dto.LabReportView;
import com.benchmark.lab_result_service.core.api.dto.LabTestItemView;
import com.benchmark.lab_result_service.core.api.dto.PatientLabHistoryView;
import com.benchmark.lab_result_service.core.api.dto.PatientSummaryView;
import com.benchmark.lab_result_service.core.api.dto.PatientView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The protocol-neutral canonical tree of contract §10 P7a: plain Java values ({@code Map}, {@code List}, {@code Long},
 * {@code Double}, {@code String}, {@code Boolean}, {@code null}) built from a response with a <b>strict, per-member type
 * rule</b>, so a type difference cannot be normalized away:
 * <ul>
 *   <li>{@code id} → {@code Long}. REST must send an integral JSON number; GraphQL must send an {@code ID} string of
 *       decimal digits (D11). Anything else (a REST string, a GraphQL number, {@code "3.0"}) fails.</li>
 *   <li>{@code resultValue} → {@code Double}, compared exactly ({@link Double#equals}, bit-equal). Must be a JSON
 *       number on every protocol.</li>
 *   <li>{@code isCritical} → {@code Boolean}; must be a JSON boolean.</li>
 *   <li>every other scalar member is a JSON string and kept verbatim (ISO dates/instants are <b>not</b> parsed, so
 *       {@code "…:00Z"} vs {@code "…:00.000Z"} would differ).</li>
 *   <li>{@code patient}/{@code latestReport} are objects ({@code latestReport} may be {@code null}),
 *       {@code reports}/{@code items} arrays; array order is kept and compared.</li>
 * </ul>
 * An unknown member fails. Object member order is not semantic in JSON and is not compared ({@code Map#equals}).
 *
 * <p>A third, independent reference is built directly from the core.api records ({@link #of(PatientSummaryView)},
 * {@link #of(PatientLabHistoryView)}): {@code LocalDate}/{@code Instant#toString}, the record's {@code long} and
 * {@code double}. It needs no JSON library, so REST and GraphQL cannot both drift the same way unnoticed. P7b adds
 * the gRPC converter next to these.
 */
public final class CanonicalTree {

    /** How an {@code id} arrives on the wire. */
    public enum IdEncoding { JSON_NUMBER, ID_STRING }

    private static final Set<String> STRINGS = Set.of("tcNo", "fullName", "birthDate", "bloodType", "doctorName",
            "sampleType", "createdAt", "testCode", "testName", "unit");
    private static final Set<String> OBJECTS = Set.of("patient", "latestReport");
    private static final Set<String> ARRAYS = Set.of("reports", "items");
    private static final Pattern DECIMAL_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private CanonicalTree() {}

    /** Canonical tree of a REST body or a GraphQL {@code data.<rootField>} value. */
    public static Object fromJson(JsonNode node, IdEncoding ids) {
        if (node == null || node.isMissingNode()) {
            throw new AssertionError("missing node");
        }
        return object(node, ids, "$");
    }

    private static Map<String, Object> object(JsonNode node, IdEncoding ids, String path) {
        if (!node.isObject()) {
            throw new AssertionError(path + ": expected an object, got " + node.getNodeType());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String key = e.getKey();
            out.put(key, member(key, e.getValue(), ids, path + "." + key));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object member(String key, JsonNode value, IdEncoding ids, String path) {
        if (key.equals("id")) {
            return id(value, ids, path);
        }
        if (key.equals("resultValue")) {
            if (!value.isNumber()) {
                throw new AssertionError(path + ": expected a JSON number, got " + value.getNodeType());
            }
            return value.doubleValue();
        }
        if (key.equals("isCritical")) {
            if (!value.isBoolean()) {
                throw new AssertionError(path + ": expected a JSON boolean, got " + value.getNodeType());
            }
            return value.booleanValue();
        }
        if (STRINGS.contains(key)) {
            if (!value.isString()) {
                throw new AssertionError(path + ": expected a JSON string, got " + value.getNodeType());
            }
            return value.asString();
        }
        if (OBJECTS.contains(key)) {
            if (value.isNull() && key.equals("latestReport")) {
                return null;
            }
            return object(value, ids, path);
        }
        if (ARRAYS.contains(key)) {
            if (!value.isArray()) {
                throw new AssertionError(path + ": expected an array, got " + value.getNodeType());
            }
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (JsonNode element : value) {
                out.add(object(element, ids, path + "[" + i++ + "]"));
            }
            return Collections.unmodifiableList(out);
        }
        throw new AssertionError(path + ": unknown member");
    }

    private static Long id(JsonNode value, IdEncoding ids, String path) {
        return switch (ids) {
            case JSON_NUMBER -> {
                if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                    throw new AssertionError(path + ": expected an integral JSON number, got " + value);
                }
                yield value.longValue();
            }
            case ID_STRING -> {
                if (!value.isString() || !DECIMAL_ID.matcher(value.asString()).matches()) {
                    throw new AssertionError(path + ": expected a GraphQL ID string of digits, got " + value);
                }
                yield Long.parseLong(value.asString());
            }
        };
    }

    // ---- independent reference from the core.api records ----

    public static Object of(PatientSummaryView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", patient(view.patient()));
        out.put("latestReport", view.latestReport() == null ? null : report(view.latestReport()));
        return Collections.unmodifiableMap(out);
    }

    public static Object of(PatientLabHistoryView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", patient(view.patient()));
        out.put("reports", view.reports().stream().map(CanonicalTree::report).toList());
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Object> patient(PatientView p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("tcNo", p.tcNo());
        out.put("fullName", p.fullName());
        out.put("birthDate", p.birthDate().toString());
        out.put("bloodType", p.bloodType());
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Object> report(LabReportView r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id());
        out.put("doctorName", r.doctorName());
        out.put("sampleType", r.sampleType());
        out.put("createdAt", r.createdAt().toString());
        out.put("items", r.items().stream().map(CanonicalTree::item).toList());
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Object> item(LabTestItemView i) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", i.id());
        out.put("testCode", i.testCode());
        out.put("testName", i.testName());
        out.put("resultValue", i.resultValue());
        out.put("unit", i.unit());
        out.put("isCritical", i.isCritical());
        return Collections.unmodifiableMap(out);
    }

    /**
     * The S2b selection ({@code reports { items { testName resultValue } }}) of a full S2 canonical tree, so the
     * over-fetch variant is checked against REST S2 data rather than only against itself.
     */
    @SuppressWarnings("unchecked")
    public static Object s2bProjection(Object s2Tree) {
        Map<String, Object> history = (Map<String, Object>) s2Tree;
        List<Object> reports = ((List<Object>) history.get("reports")).stream().map(r -> {
            List<Object> items = ((List<Object>) ((Map<String, Object>) r).get("items")).stream().map(i -> {
                Map<String, Object> item = (Map<String, Object>) i;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("testName", item.get("testName"));
                out.put("resultValue", item.get("resultValue"));
                return (Object) Collections.unmodifiableMap(out);
            }).toList();
            return (Object) Map.<String, Object>of("items", items);
        }).toList();
        return Map.<String, Object>of("reports", reports);
    }
}
