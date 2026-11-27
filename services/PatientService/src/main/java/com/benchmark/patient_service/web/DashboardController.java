package com.benchmark.patient_service.web;

import com.benchmark.patient_service.client.Scenario;
import com.benchmark.patient_service.dto.DashboardResponse;
import com.benchmark.patient_service.service.LabRouter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bff")
public class DashboardController
{
    private final LabRouter router;

    public DashboardController(LabRouter router) {
        this.router = router;
    }

    // Örnek: GET /api/bff/patients/3/lab?protocol=grpc&scenario=S1
    @GetMapping("/patients/{patientId}/lab")
    public DashboardResponse lab(@PathVariable long patientId,
                                 @RequestParam String protocol,
                                 @RequestParam(defaultValue = "S2") Scenario scenario) {
        return router.fetch(protocol, scenario, patientId);
    }
}
