package com.cras.controller;

import com.cras.service.AutopilotService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/autopilot")
@CrossOrigin
public class AutopilotController {

    private final AutopilotService autopilot;

    public AutopilotController(AutopilotService autopilot) {
        this.autopilot = autopilot;
    }

    @GetMapping
    public Map<String, Object> status() {
        return autopilot.status();
    }

    @PostMapping("/configure")
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    public Map<String, Object> configure(@RequestParam boolean enabled,
                                         @RequestParam(defaultValue = "true") boolean preemption,
                                         Authentication auth) {
        autopilot.configure(enabled, preemption, auth.getName());
        return autopilot.status();
    }

    /** Run exactly one scheduler pass right now. */
    @PostMapping("/step")
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    public Map<String, Object> step(Authentication auth) {
        Map<String, Object> r = autopilot.tick(auth.getName());
        r.put("status", autopilot.status());
        return r;
    }
}
