package com.cras.service;

import com.cras.entity.EmergencyRequest;
import com.cras.entity.RequestStatus;
import com.cras.entity.Resource;
import com.cras.repository.RequestRepository;
import com.cras.repository.ResourceRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Per-resource priority scheduler.
 *
 * Phase III changes:
 *  - recalculated scores are SAVED, so the database column no longer lags behind the queue
 *  - dispatch uses BACKFILLING: if the top request does not fit the stock, the next request
 *    that fits is served, instead of the whole queue blocking behind one big request
 *  - guard: a non-critical request (severity < 8) may NOT take stock while a higher-ranked
 *    CRITICAL request is still waiting for it (reservation for critical requests)
 */
@Service
public class SchedulerService {

    private static final int CRITICAL_SEVERITY = 8;

    private final RequestRepository requestRepository;
    private final ResourceRepository resourceRepository;
    private final PriorityService priorityService;
    private final AllocationService allocationService;

    public SchedulerService(RequestRepository requestRepository,
                            ResourceRepository resourceRepository,
                            PriorityService priorityService,
                            AllocationService allocationService) {
        this.requestRepository = requestRepository;
        this.resourceRepository = resourceRepository;
        this.priorityService = priorityService;
        this.allocationService = allocationService;
    }

    @Transactional
    public List<EmergencyRequest> getQueue(Long resourceId) {
        List<EmergencyRequest> queue =
                requestRepository.findByResourceIdAndStatusOrderByPriorityScoreDescCreatedAtAsc(
                        resourceId, RequestStatus.PENDING);
        queue.forEach(r -> r.setPriorityScore(priorityService.calculate(r)));
        queue.sort((a, b) -> {
            int p = Integer.compare(b.getPriorityScore(), a.getPriorityScore());
            return p != 0 ? p : a.getCreatedAt().compareTo(b.getCreatedAt());
        });
        requestRepository.saveAll(queue);              // keep the stored score fresh
        return queue;
    }

    @Transactional
    public List<EmergencyRequest> getAllPending() {
        List<EmergencyRequest> queue =
                requestRepository.findByStatusOrderByPriorityScoreDescCreatedAtAsc(
                        RequestStatus.PENDING);
        queue.forEach(r -> r.setPriorityScore(priorityService.calculate(r)));
        queue.sort((a, b) -> Integer.compare(b.getPriorityScore(), a.getPriorityScore()));
        requestRepository.saveAll(queue);
        return queue;
    }

    /** Not transactional on purpose: each allocation attempt is its own transaction. */
    public AllocationResult dispatch(Long resourceId, String actor) {
        List<EmergencyRequest> queue = getQueue(resourceId);
        if (queue.isEmpty()) {
            throw new NoSuchElementException("No pending requests for this resource");
        }
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new NoSuchElementException("Resource not found"));
        int stock = resource.getAvailableQuantity();

        for (int i = 0; i < queue.size(); i++) {
            EmergencyRequest candidate = queue.get(i);
            if (candidate.getQuantity() > stock) {
                continue;                                   // does not fit: try the next one
            }
            if (i > 0 && candidate.getSeverity() < CRITICAL_SEVERITY
                    && criticalBlockedAhead(queue, i, stock)) {
                continue;                                   // reserve stock for the critical request
            }
            try {
                var allocation = allocationService.allocate(candidate.getId(), actor);
                return new AllocationResult(i == 0 ? "ALLOCATED" : "ALLOCATED_BACKFILL",
                        allocation.getId(), candidate.getId());
            } catch (InsufficientStockException raced) {
                stock = raced.getAvailable();               // someone else took it: re-evaluate
            }
        }
        return new AllocationResult("WAITING_OR_PREEMPTION_REQUIRED", null, queue.get(0).getId());
    }

    private boolean criticalBlockedAhead(List<EmergencyRequest> queue, int index, int stock) {
        for (int j = 0; j < index; j++) {
            EmergencyRequest ahead = queue.get(j);
            if (ahead.getSeverity() >= CRITICAL_SEVERITY && ahead.getQuantity() > stock) {
                return true;
            }
        }
        return false;
    }

    public record AllocationResult(String result, Long allocationId, Long requestId) {}
}
