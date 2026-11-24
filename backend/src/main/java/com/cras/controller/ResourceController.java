package com.cras.controller;

import com.cras.dto.ResourceCreate;
import com.cras.entity.Resource;
import com.cras.repository.ResourceRepository;
import com.cras.service.AuditService;
import com.cras.service.DbSession;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/resources")
@CrossOrigin
public class ResourceController {

    private final ResourceRepository repository;
    private final AuditService auditService;
    private final DbSession dbSession;
    private final com.cras.service.RealtimeService realtime;

    public ResourceController(ResourceRepository repository, AuditService auditService, DbSession dbSession,
                              com.cras.service.RealtimeService realtime) {
        this.repository = repository;
        this.auditService = auditService;
        this.dbSession = dbSession;
        this.realtime = realtime;
    }

    @GetMapping
    public List<Resource> getAll() {
        return repository.findAll();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    @Transactional
    public Resource create(@Valid @RequestBody ResourceCreate body, Authentication auth) {
        Resource saved = repository.save(new Resource(body.name(), body.unit(), body.availableQuantity()));
        auditService.log(auth.getName(), "RESOURCE_CREATED",
                saved.getName() + " (" + saved.getUnit() + ") opening stock " + saved.getAvailableQuantity());
        return saved;
    }

    /** Absolute stock correction (stock count). Locked and audited. */
    @PutMapping("/{id}/stock")
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    @Transactional
    public Resource updateStock(@PathVariable Long id, @RequestParam int quantity,
                                Authentication auth) {
        if (quantity < 0) throw new IllegalArgumentException("Quantity cannot be negative");
        Resource r = repository.findByIdForUpdate(id)               // same lock as allocation
                .orElseThrow(() -> new NoSuchElementException("Resource not found"));
        int before = r.getAvailableQuantity();
        dbSession.tag(auth.getName(), "STOCK_SET");
        r.setAvailableQuantity(quantity);
        Resource saved = repository.save(r);
        auditService.log(auth.getName(), "STOCK_SET",
                r.getName() + ": " + before + " -> " + quantity);
        realtime.publish("STOCK_CHANGED", java.util.Map.of("resourceId", id));
        return saved;
    }

    /** Relative change (truck arrives: +500, spoilage: -20). Safe under concurrent allocations. */
    @PostMapping("/{id}/restock")
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    @Transactional
    public Resource restock(@PathVariable Long id, @RequestParam int delta, Authentication auth) {
        Resource r = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("Resource not found"));
        int after = r.getAvailableQuantity() + delta;
        if (after < 0) throw new IllegalArgumentException("Stock cannot go below zero");
        dbSession.tag(auth.getName(), delta >= 0 ? "RESTOCK" : "WRITE_OFF");
        r.setAvailableQuantity(after);
        Resource saved = repository.save(r);
        auditService.log(auth.getName(), "STOCK_DELTA",
                r.getName() + ": " + (delta >= 0 ? "+" : "") + delta + " -> " + after);
        realtime.publish("STOCK_CHANGED", java.util.Map.of("resourceId", id));
        return saved;
    }
}
