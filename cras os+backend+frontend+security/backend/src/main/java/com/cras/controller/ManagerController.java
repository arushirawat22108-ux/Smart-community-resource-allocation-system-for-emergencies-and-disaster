package com.cras.controller;

import com.cras.entity.*;
import com.cras.repository.*;
import com.cras.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/manager")
@CrossOrigin
public class ManagerController {

    private final SchedulerService schedulerService;
    private final AllocationService allocationService;
    private final RequestRepository requestRepository;
    private final ResourceRepository resourceRepository;
    private final AllocationRepository allocationRepository;

    public ManagerController(SchedulerService schedulerService,
                             AllocationService allocationService,
                             RequestRepository requestRepository,
                             ResourceRepository resourceRepository,
                             AllocationRepository allocationRepository) {
        this.schedulerService = schedulerService;
        this.allocationService = allocationService;
        this.requestRepository = requestRepository;
        this.resourceRepository = resourceRepository;
        this.allocationRepository = allocationRepository;
    }

    @GetMapping("/queue/{resourceId}")
    public List<EmergencyRequest> queue(@PathVariable Long resourceId) {
        return schedulerService.getQueue(resourceId);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        long pending = requestRepository
                .findByStatusOrderByPriorityScoreDescCreatedAtAsc(RequestStatus.PENDING).size();

        long allocated = requestRepository
                .findByStatusOrderByPriorityScoreDescCreatedAtAsc(RequestStatus.ALLOCATED).size();

        long preempted = requestRepository
                .findByStatusOrderByPriorityScoreDescCreatedAtAsc(RequestStatus.PREEMPTED).size();

        long completed = requestRepository
                .findByStatusOrderByPriorityScoreDescCreatedAtAsc(RequestStatus.COMPLETED).size();

        return Map.of(
                "resources", resourceRepository.count(),
                "pendingRequests", pending,
                "allocatedRequests", allocated,
                "preemptedRequests", preempted,
                "completedRequests", completed,
                "activeAllocations", allocationRepository.count()
        );
    }

    @GetMapping("/queue")
    public List<EmergencyRequest> allPending() {
        return requestRepository.findByStatusOrderByPriorityScoreDescCreatedAtAsc(
                RequestStatus.PENDING);
    }

    @PostMapping("/dispatch/{resourceId}")
    public SchedulerService.AllocationResult dispatch(
            @PathVariable Long resourceId,
            Authentication auth) {
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
    public EmergencyRequest complete(@PathVariable Long requestId) {
        EmergencyRequest r = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found"));
        r.setStatus(RequestStatus.COMPLETED);
        return requestRepository.save(r);
    }
}
