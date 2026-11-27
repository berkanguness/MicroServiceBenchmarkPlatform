package com.benchmark.patient_service.client;

import com.benchmark.patient_service.dto.LabItemDto;
import com.benchmark.patient_service.dto.LabReportDto;
import com.benchmark.patient_service.dto.PatientDto;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

final class LabJson {

    private LabJson() {}

    static PatientDto patient(JsonNode n) {
        return new PatientDto(
                n.path("id").asLong(),
                n.path("tcNo").asString(),
                n.path("fullName").asString(),
                n.path("birthDate").asString(),
                n.path("bloodType").asString());
    }

    static List<LabReportDto> reports(JsonNode root, Scenario scenario) {
        List<LabReportDto> out = new ArrayList<>();
        if (scenario == Scenario.S1) {
            JsonNode latest = root.path("latestReport");
            if (latest.isObject()) {
                out.add(report(latest));
            }
        } else {
            for (JsonNode r : root.path("reports")) {
                out.add(report(r));
            }
        }
        return out;
    }

    private static LabReportDto report(JsonNode r) {
        List<LabItemDto> items = new ArrayList<>();
        for (JsonNode i : r.path("items")) {
            items.add(new LabItemDto(
                    i.path("id").asLong(),
                    i.path("testCode").asString(),
                    i.path("testName").asString(),
                    i.path("resultValue").asDouble(),
                    i.path("unit").asString(),
                    i.path("isCritical").asBoolean(false)));
        }
        return new LabReportDto(
                r.path("id").asLong(),
                r.path("doctorName").asString(),
                r.path("sampleType").asString(),
                r.path("createdAt").asString(),
                items);
    }
}