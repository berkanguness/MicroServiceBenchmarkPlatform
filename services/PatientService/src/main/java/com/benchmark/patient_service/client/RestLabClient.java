package com.benchmark.patient_service.client;

import com.benchmark.patient_service.dto.DashboardResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RestLabClient implements LabClient
{
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;

    public RestLabClient(@Value("${lab.base-url}") String baseUrl){
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String protocol(){
        return "rest";
    }

    @Override
    public DashboardResponse fetch(Scenario scenario, long patientId){
        String path = "/api/v1/patients/" + patientId + (scenario == Scenario.S1 ? "/summary" : "/lab-history");

        long start = System.nanoTime();
        byte[] body = rest.get().uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(byte[].class);
        double ms = (System.nanoTime() - start) / 1_000_000.0;


        JsonNode root = JSON.readTree(body);

        return new DashboardResponse(protocol(), scenario.name(), ms, body.length, "utf8-http-body", LabJson.patient(root.path("patient")), LabJson.reports(root, scenario));
    }

}
