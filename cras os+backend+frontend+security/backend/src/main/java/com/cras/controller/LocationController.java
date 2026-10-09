package com.cras.controller;

import com.cras.dto.LocationCreate;
import com.cras.entity.Location;
import com.cras.repository.LocationRepository;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/locations")
@CrossOrigin
public class LocationController {

    private final LocationRepository repository;

    public LocationController(LocationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Location> getAll() {
        return repository.findAll();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    public Location create(@Valid @RequestBody LocationCreate body) {
        return repository.save(new Location(
                body.name(), body.latitude(), body.longitude(), body.riskLevel()));
    }
}
