package com.cras.controller;

import com.cras.entity.*;
import com.cras.repository.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final AllocationRepository allocationRepository;

    public AdminController(UserRepository userRepository,
                           AuditLogRepository auditLogRepository,
                           AllocationRepository allocationRepository) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.allocationRepository = allocationRepository;
    }

    @GetMapping("/users")
    public List<User> users() {
        return userRepository.findAll();
    }

    @GetMapping("/audit")
    public List<AuditLog> audit() {
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc();
    }

    @GetMapping("/allocations")
    public List<Allocation> allocations() {
        return allocationRepository.findTop100ByOrderByAllocatedAtDesc();
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return Map.of(
            "users", userRepository.count(),
            "allocations", allocationRepository.count(),
            "auditEvents", auditLogRepository.count()
        );
    }
}
