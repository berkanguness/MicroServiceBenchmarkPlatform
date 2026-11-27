package com.benchmark.patient_service.client;

import com.benchmark.patient_service.dto.DashboardResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class GraphQlLabClient implements LabClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String S1 = """
            query S1($id: ID!) {
              patientSummary(patientId: $id) {
                patient { id tcNo fullName birthDate bloodType }
                latestReport { id doctorName sampleType createdAt
                  items { id testCode testName resultValue unit isCritical } }
              }
            }""";

    private static final String S2 = """
            query S2($id: ID!) {
              patientLabHistory(patientId: $id) {
                patient { id tcNo fullName birthDate bloodType }
                reports { id doctorName sampleType createdAt
                  items { id testCode testName resultValue unit isCritical } }
              }
            }""";

    private final RestClient rest = RestClient.create();
    private final String graphqlUrl;

    public GraphQlLabClient(@Value("${lab.base-url}") String baseUrl) {
        this.graphqlUrl = baseUrl + "/graphql";
    }

    @Override
    public String protocol() {
        return "graphql";
    }

    @Override
    public DashboardResponse fetch(Scenario scenario, long patientId) {
        ObjectNode request = JSON.createObjectNode();
        request.put("query", scenario == Scenario.S1 ? S1 : S2);
        request.put("operationName", scenario.name());
        request.putObject("variables").put("id", Long.toString(patientId));

        long start = System.nanoTime();
        byte[] body = rest.post().uri(graphqlUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsBytes(request))
                .retrieve()
                .body(byte[].class);
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        JsonNode response = JSON.readTree(body);
        String rootField = scenario == Scenario.S1 ? "patientSummary" : "patientLabHistory";
        JsonNode data = response.path("data").path(rootField);

        // GraphQL hataları HTTP 200 içinde gelir
        if (response.path("errors").size() > 0 || !data.isObject()) {
            String code = response.path("errors").path(0).path("extensions").path("code").asString();
            HttpStatus status = switch (code) {
                case "PATIENT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                case "INVALID_ARGUMENT" -> HttpStatus.BAD_REQUEST;
                case "UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.BAD_GATEWAY;
            };
            throw new LabClientException(status, "GraphQL error " + code);
        }

        return new DashboardResponse(protocol(), scenario.name(), ms, body.length, "utf8-http-body",
                LabJson.patient(data.path("patient")), LabJson.reports(data, scenario));
    }
}