package com.cras.service;

import com.cras.entity.*;
import com.cras.repository.*;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AllocationService {

    /** Only requests at or above this severity may preempt other allocations. */
    private static final int PREEMPT_MIN_SEVERITY = 8;

    private final ResourceRepository resourceRepository;
    private final RequestRepository requestRepository;
    private final AllocationRepository allocationRepository;
    private final PriorityService priorityService;
    private final AuditService auditService;
    private final RealtimeService realtimeService;
    private final DbSession dbSession;

    public AllocationService(ResourceRepository resourceRepository,
                             RequestRepository requestRepository,
                             AllocationRepository allocationRepository,
                             PriorityService priorityService,
                             AuditService auditService,
                             RealtimeService realtimeService,
                             DbSession dbSession) {
        this.resourceRepository = resourceRepository;
        this.requestRepository = requestRepository;
        this.allocationRepository = allocationRepository;
        this.priorityService = priorityService;
        this.auditService = auditService;
        this.realtimeService = realtimeService;
        this.dbSession = dbSession;
    }

    /*
     * DBMS concurrency protection: the resource row is locked (SELECT ... FOR UPDATE).
     * Inventory change + request status + allocation record + audit log commit together.
     */
    @Transactional
    public Allocation allocate(Long requestId, String actor) {
        dbSession.tag(actor, "ALLOCATE");                         // ledger attribution
        EmergencyRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("Request not found"));
        if (request.getStatus() != RequestStatus.PENDING &&
            request.getStatus() != RequestStatus.PREEMPTED) {
            throw new IllegalStateException("Request is not waiting for allocation");
        }

        Resource resource = resourceRepository.findByIdForUpdate(request.getResource().getId())
                .orElseThrow(() -> new NoSuchElementException("Resource not found"));

        if (resource.getAvailableQuantity() < request.getQuantity()) {
            throw new InsufficientStockException(resource.getAvailableQuantity(), request.getQuantity());
        }

        resource.setAvailableQuantity(resource.getAvailableQuantity() - request.getQuantity());
        request.setStatus(RequestStatus.ALLOCATED);
        request.setPriorityScore(priorityService.calculate(request));

        Allocation allocation = new Allocation(request, resource, request.getQuantity());
        resourceRepository.save(resource);
        requestRepository.save(request);
        Allocation saved = allocationRepository.save(allocation);

        auditService.log(actor, "ALLOCATE", "Request " + requestId + " allocated "
                + request.getQuantity() + " " + resource.getUnit());
        realtimeService.publish("ALLOCATION_UPDATED", saved.getId());
        return saved;
    }

    /*
     * OS preemption: a CRITICAL waiting request reclaims logically reserved stock from
     * lower-priority ACTIVE allocations, lowest priority first.
     * Phase III fixes: only critical requests may preempt, and preempted requests go back
     * to PENDING so they re-enter the scheduler queue (keeping their original arrival time,
     * hence their aging).
     */
    @Transactional
    public Allocation preemptFor(Long criticalRequestId, String actor) {
        dbSession.tag(actor, "PREEMPT_RELEASE");
        EmergencyRequest critical = requestRepository.findById(criticalRequestId)
                .orElseThrow(() -> new NoSuchElementException("Critical request not found"));
        if (critical.getStatus() != RequestStatus.PENDING &&
            critical.getStatus() != RequestStatus.PREEMPTED) {
            throw new IllegalStateException("Critical request is not waiting");
        }
        if (critical.getSeverity() < PREEMPT_MIN_SEVERITY) {
            throw new IllegalStateException("Only requests with severity >= "
                    + PREEMPT_MIN_SEVERITY + " may preempt other allocations");
        }

        Resource resource = resourceRepository.findByIdForUpdate(critical.getResource().getId())
                .orElseThrow(() -> new NoSuchElementException("Resource not found"));
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

        // recalculate victims' scores first: the stored value may be stale
        victims.forEach(a -> a.getRequest().setPriorityScore(
                priorityService.calculate(a.getRequest())));
        victims.removeIf(a -> a.getRequest().getPriorityScore() >= critical.getPriorityScore());
        victims.sort(Comparator.comparingInt(a -> a.getRequest().getPriorityScore()));

        int released = 0;
        List<Allocation> chosen = new ArrayList<>();
        for (Allocation victim : victims) {
            chosen.add(victim);
            released += victim.getQuantity();
            if (released >= needed) break;
        }
        if (released < needed) {
            // nothing has been changed yet, so the transaction simply ends without side effects
            throw new InsufficientStockException(available + released, required);
        }

        for (Allocation victim : chosen) {
            victim.setStatus(AllocationStatus.PREEMPTED);
            victim.getRequest().setStatus(RequestStatus.PENDING);     // back into the queue
            allocationRepository.save(victim);
            requestRepository.save(victim.getRequest());
            auditService.log(actor, "PREEMPT", "Allocation " + victim.getId()
                    + " preempted for request " + criticalRequestId
                    + "; request " + victim.getRequest().getId() + " re-queued");
        }
        resource.setAvailableQuantity(resource.getAvailableQuantity() + released);
        resourceRepository.saveAndFlush(resource);                 // flush now, while the PREEMPT tag is active
        realtimeService.publish("PREEMPTION", criticalRequestId);
        return allocate(criticalRequestId, actor);
    }

    /*
     * Phase III: completing a request is a real state transition now.
     * ALLOCATED -> COMPLETED, its ACTIVE allocations become COMPLETED, and it is audited.
     */
    @Transactional
    public EmergencyRequest complete(Long requestId, String actor) {
        EmergencyRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new NoSuchElementException("Request not found"));
        if (request.getStatus() != RequestStatus.ALLOCATED) {
            throw new IllegalStateException("Only an ALLOCATED request can be completed");
        }
        request.setStatus(RequestStatus.COMPLETED);
        requestRepository.save(request);
        for (Allocation a : allocationRepository.findByRequestId(requestId)) {
            if (a.getStatus() == AllocationStatus.ACTIVE) {
                a.setStatus(AllocationStatus.COMPLETED);
                allocationRepository.save(a);
            }
        }
        auditService.log(actor, "COMPLETE", "Request " + requestId + " completed");
        realtimeService.publish("REQUEST_COMPLETED", requestId);
        return request;
    }
}
