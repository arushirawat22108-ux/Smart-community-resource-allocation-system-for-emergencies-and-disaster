package com.cras.controller;

import com.cras.service.ConcurrencyLabService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Concurrency experiments on a scratch table. Manager/admin only (they open many connections). */
@RestController
@RequestMapping("/api/lab")
@CrossOrigin
@PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
public class LabController {

    private final ConcurrencyLabService lab;

    public LabController(ConcurrencyLabService lab) {
        this.lab = lab;
    }

    @PostMapping("/race")
    public Map<String, Object> race(@RequestParam String mode,
                                    @RequestParam(defaultValue = "15") int threads,
                                    @RequestParam(defaultValue = "10") int units,
                                    @RequestParam(defaultValue = "100") int stock) {
        return lab.race(mode, threads, units, stock);
    }

    @PostMapping("/isolation")
    public Map<String, Object> isolation(@RequestParam String level) {
        return lab.isolation(level);
    }

    @PostMapping("/deadlock")
    public Map<String, Object> deadlock(@RequestParam(defaultValue = "false") boolean ordered) {
        return lab.deadlock(ordered);
    }
}
