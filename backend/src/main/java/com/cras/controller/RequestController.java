package com.cras.controller;

import com.cras.dto.RequestCreate;
import com.cras.dto.RequestView;
import com.cras.service.RequestService;
import com.cras.service.SeverityService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requests")
@CrossOrigin
public class RequestController {

    private final RequestService service;

    public RequestController(RequestService service) {
        this.service = service;
    }

    static boolean privileged(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_RESOURCE_MANAGER"));
    }

    /** Managers/admins: every request. Community users: only their own. */
    @GetMapping
    public List<RequestView> all(Authentication auth) {
        return service.list(auth.getName(), privileged(auth));
    }

    /** Public queue (requester identities masked for community users). */
    @GetMapping("/pending")
    public List<RequestView> pending(Authentication auth) {
        return service.pending(auth.getName(), privileged(auth));
    }

    @PostMapping
    public RequestView create(@Valid @RequestBody RequestCreate body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                              Authentication auth) {
        return service.create(body, auth.getName(), idemKey);
    }

    /** Severity estimate for the form (nothing is saved). */
    @PostMapping("/estimate")
    public SeverityService.Estimate estimate(@Valid @RequestBody RequestCreate body) {
        return service.estimate(body);
    }

    @PostMapping("/{id}/cancel")
    public RequestView cancel(@PathVariable Long id, Authentication auth) {
        return service.cancel(id, auth.getName(), privileged(auth));
    }

    @GetMapping("/{id}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable Long id, Authentication auth) {
        return service.timeline(id, auth.getName(), privileged(auth));
    }
}
