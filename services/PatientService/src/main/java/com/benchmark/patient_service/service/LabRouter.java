package com.benchmark.patient_service.service;

import com.benchmark.patient_service.client.LabClient;
import com.benchmark.patient_service.client.Scenario;
import com.benchmark.patient_service.dto.DashboardResponse;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LabRouter
{
    private final Map<String, LabClient> clients = new HashMap<>();

    public LabRouter(List<LabClient> all) {
        all.forEach(c -> clients.put(c.protocol(), c));
    }

    public DashboardResponse fetch(String protocol, Scenario scenario, long patientId) {
        LabClient client = clients.get(protocol.toLowerCase(Locale.ROOT));
        if (client == null) {
            throw new IllegalArgumentException("Bilinmeyen protokol: " + protocol + " (rest, grpc, graphql)");
        }
        return client.fetch(scenario, patientId);
    }
}
