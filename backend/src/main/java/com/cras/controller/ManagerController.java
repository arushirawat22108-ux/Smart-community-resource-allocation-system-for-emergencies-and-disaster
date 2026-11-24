package com.cras.controller;

import com.cras.dto.RequestView;
import com.cras.entity.Allocation;
import com.cras.entity.EmergencyRequest;
import com.cras.repository.AllocationRepository;
import com.cras.service.AllocationService;
import com.cras.service.RequestService;
import com.cras.service.SchedulerService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/manager")
@CrossOrigin
@PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
public class ManagerController {

    private final SchedulerService schedulerService;
    private final AllocationService allocationService;
    private final RequestService requestService;
    private final AllocationRepository allocationRepository;

    public ManagerController(SchedulerService schedulerService, AllocationService allocationService,
                             RequestService requestService, AllocationRepository allocationRepository) {
        this.schedulerService = schedulerService;
        this.allocationService = allocationService;
        this.requestService = requestService;
        this.allocationRepository = allocationRepository;
    }

    @GetMapping("/queue/{resourceId}")
    public List<RequestView> queue(@PathVariable Long resourceId, Authentication auth) {
        return requestService.toViews(schedulerService.getQueue(resourceId), auth.getName(), true);
    }

    @GetMapping("/queue")
    public List<RequestView> allPending(Authentication auth) {
        return requestService.toViews(schedulerService.getAllPending(), auth.getName(), true);
    }

    /** Manager corrects the automatic severity estimate (audited). */
    @PostMapping("/severity/{requestId}")
    public RequestView overrideSeverity(@PathVariable Long requestId, @RequestParam int value, Authentication auth) {
        return requestService.overrideSeverity(requestId, value, auth.getName());
    }

    /** Recent allocations, newest first (flattened for the UI). */
    @GetMapping("/allocations")
    public List<Map<String, Object>> allocations() {
        return allocationRepository.findTop100ByOrderByAllocatedAtDesc().stream().map(a -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("requestId", a.getRequest().getId());
            m.put("resource", a.getResource().getName());
            m.put("unit", a.getResource().getUnit());
            m.put("quantity", a.getQuantity());
            m.put("status", a.getStatus().name());
            m.put("allocatedAt", a.getAllocatedAt().toString());
            m.put("requester", a.getRequest().getRequesterEmail());
            m.put("severity", a.getRequest().getSeverity());
            return m;
        }).toList();
    }

    @PostMapping("/dispatch/{resourceId}")
    public SchedulerService.AllocationResult dispatch(@PathVariable Long resourceId, Authentication auth) {
        return schedulerService.dispatch(resourceId, auth.getName());
    }

    @PostMapping("/allocate/{requestId}")
    public Allocation allocate(@PathVariable Long requestId, Authentication auth) {
        return allocationService.allocate(requestId, auth.getName());
    }

    @PostMapping("/preempt/{requestId}")
    public Allocation preempt(@PathVariable Long requestId, Authentication auth) {
        return allocationService.preemptFor(requestId, auth.getName());
    }

    @PostMapping("/complete/{requestId}")
    public RequestView complete(@PathVariable Long requestId, Authentication auth) {
        return requestService.toView(allocationService.complete(requestId, auth.getName()), auth.getName(), true);
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of(
                "module", "CRAS Phase III Manager",
                "features", "backfilling, critical reservation, preemption (sev>=8), AI priority, complete()"
        );
    }
}
