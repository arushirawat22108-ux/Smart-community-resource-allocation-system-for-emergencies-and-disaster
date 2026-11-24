package com.cras.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin
public class HealthController {

    @Value("${cras.priority.mode:phase2}")
    private String priorityMode;

    @Value("${cras.ai.enabled:false}")
    private boolean aiEnabled;

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "phase", "III",
                "priorityMode", priorityMode,
                "aiEnabled", aiEnabled
        );
    }
}
