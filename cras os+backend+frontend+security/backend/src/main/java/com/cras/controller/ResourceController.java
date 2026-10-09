package com.cras.controller;

import com.cras.dto.ResourceCreate;
import com.cras.entity.Resource;
import com.cras.repository.ResourceRepository;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/resources")
@CrossOrigin
public class ResourceController {

    private final ResourceRepository repository;

    public ResourceController(ResourceRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Resource> getAll() {
        return repository.findAll();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    public Resource create(@Valid @RequestBody ResourceCreate body) {
        return repository.save(new Resource(
                body.name(), body.unit(), body.availableQuantity()));
    }

    @PutMapping("/{id}/stock")
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    public Resource updateStock(@PathVariable Long id, @RequestParam int quantity) {
        Resource r = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Resource not found"));
        if (quantity < 0) throw new IllegalArgumentException("Quantity cannot be negative");
        r.setAvailableQuantity(quantity);
        return repository.save(r);
    }
}
