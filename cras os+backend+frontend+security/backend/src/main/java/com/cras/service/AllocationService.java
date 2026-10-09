package com.cras.service;

import com.cras.entity.*;
import com.cras.repository.*;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AllocationService {

    private final ResourceRepository resourceRepository;
    private final RequestRepository requestRepository;
    private final AllocationRepository allocationRepository;
    private final PriorityService priorityService;
    private final AuditService auditService;
    private final RealtimeService realtimeService;

    public AllocationService(ResourceRepository resourceRepository,
                             RequestRepository requestRepository,
                             AllocationRepository allocationRepository,
                             PriorityService priorityService,
                             AuditService auditService,
                             RealtimeService realtimeService) {
        this.resourceRepository = resourceRepository;
        this.requestRepository = requestRepository;
        this.allocationRepository = allocationRepository;
        this.priorityService = priorityService;
        this.auditService = auditService;
        this.realtimeService = realtimeService;
    }

    /*
     * DBMS concurrency protection:
     * the resource row is locked with SELECT ... FOR UPDATE.
     * The inventory change and allocation record are committed
     * in one transaction.
     */
    @Transactional
    public Allocation allocate(Long requestId, String actor) {
        EmergencyRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found"));

        if (request.getStatus() != RequestStatus.PENDING &&
            request.getStatus() != RequestStatus.PREEMPTED) {
            throw new RuntimeException("Request is not waiting for allocation");
        }

        Resource resource = resourceRepository.findByIdForUpdate(
                        request.getResource().getId())
                .orElseThrow(() -> new RuntimeException("Resource not found"));

        if (resource.getAvailableQuantity() < request.getQuantity()) {
            throw new RuntimeException(
                    "Insufficient inventory. Available=" +
                    resource.getAvailableQuantity() +
                    ", requested=" + request.getQuantity());
        }

        resource.setAvailableQuantity(
                resource.getAvailableQuantity() - request.getQuantity());

        request.setStatus(RequestStatus.ALLOCATED);
        request.setPriorityScore(priorityService.calculate(request));

        Allocation allocation =
                new Allocation(request, resource, request.getQuantity());

        resourceRepository.save(resource);
        requestRepository.save(request);

        Allocation saved = allocationRepository.save(allocation);

        auditService.log(actor, "ALLOCATE",
                "Request " + requestId + " allocated " +
                request.getQuantity() + " " + resource.getUnit());

        realtimeService.publish("ALLOCATION_UPDATED", saved.getId());

        return saved;
    }

    /*
     * OS preemption:
     * a critical waiting request may reclaim enough logically reserved
     * inventory from one or more lower-priority ACTIVE allocations.
     * Victims are selected from lowest priority upward.
     */
    @Transactional
    public Allocation preemptFor(Long criticalRequestId, String actor) {
        EmergencyRequest critical = requestRepository.findById(criticalRequestId)
                .orElseThrow(() -> new RuntimeException("Critical request not found"));

        if (critical.getStatus() != RequestStatus.PENDING &&
            critical.getStatus() != RequestStatus.PREEMPTED) {
            throw new RuntimeException("Critical request is not waiting");
        }

        Resource resource = resourceRepository.findByIdForUpdate(
                critical.getResource().getId()).orElseThrow();

        critical.setPriorityScore(priorityService.calculate(critical));

        int required = critical.getQuantity();
        int available = resource.getAvailableQuantity();

        if (available >= required) {
            return allocate(criticalRequestId, actor);
        }

        int needed = required - available;

        List<Allocation> victims = new ArrayList<>(
                allocationRepository.findByResourceIdAndStatusOrderByQuantityAsc(
                        resource.getId(), AllocationStatus.ACTIVE));

        victims.removeIf(a ->
                a.getRequest().getPriorityScore() >= critical.getPriorityScore());

        victims.sort(Comparator.comparingInt(
                a -> a.getRequest().getPriorityScore()));

        int released = 0;

        for (Allocation victim : victims) {
            victim.setStatus(AllocationStatus.PREEMPTED);
            victim.getRequest().setStatus(RequestStatus.PREEMPTED);

            released += victim.getQuantity();

            allocationRepository.save(victim);
            requestRepository.save(victim.getRequest());

            auditService.log(actor, "PREEMPT",
                    "Allocation " + victim.getId() +
                    " preempted for request " + criticalRequestId);

            if (released >= needed) break;
        }

        if (released < needed) {
            throw new RuntimeException(
                    "Not enough lower-priority allocations available for preemption");
        }

        resource.setAvailableQuantity(
                resource.getAvailableQuantity() + released);
        resourceRepository.save(resource);

        realtimeService.publish("PREEMPTION", criticalRequestId);

        return allocate(criticalRequestId, actor);
    }
}
