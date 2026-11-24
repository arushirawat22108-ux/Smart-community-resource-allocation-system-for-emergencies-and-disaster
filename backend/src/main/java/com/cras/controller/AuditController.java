package com.cras.controller;

import com.cras.entity.AuditLog;
import com.cras.service.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@CrossOrigin
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public List<AuditLog> recent() {
        return auditService.recent();
    }

    /** Recomputes the whole SHA-256 hash chain and reports the first broken link, if any. */
    @GetMapping("/verify")
    public AuditService.ChainReport verify() {
        return auditService.verify();
    }
}
