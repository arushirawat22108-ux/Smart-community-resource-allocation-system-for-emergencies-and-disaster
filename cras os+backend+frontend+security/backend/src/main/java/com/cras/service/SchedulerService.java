package com.cras.service;

import com.cras.entity.*;
import com.cras.repository.RequestRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SchedulerService {

    private final RequestRepository requestRepository;
    private final PriorityService priorityService;
    private final AllocationService allocationService;

    public SchedulerService(RequestRepository requestRepository,
                            PriorityService priorityService,
                            AllocationService allocationService) {
        this.requestRepository = requestRepository;
        this.priorityService = priorityService;
        this.allocationService = allocationService;
    }

    /*
     * Each resource has its own ready/waiting queue.
     * Aging is recalculated whenever the queue is viewed.
     */
    public List<EmergencyRequest> getQueue(Long resourceId) {
        List<EmergencyRequest> queue =
                requestRepository.findByResourceIdAndStatusOrderByPriorityScoreDescCreatedAtAsc(
                        resourceId, RequestStatus.PENDING);

        queue.forEach(r -> r.setPriorityScore(priorityService.calculate(r)));

        queue.sort((a, b) -> {
            int p = Integer.compare(b.getPriorityScore(), a.getPriorityScore());
            return p != 0 ? p : a.getCreatedAt().compareTo(b.getCreatedAt());
        });

        return queue;
    }

    public List<EmergencyRequest> getAllPending() {
        List<EmergencyRequest> queue =
                requestRepository.findByStatusOrderByPriorityScoreDescCreatedAtAsc(
                        RequestStatus.PENDING);
        queue.forEach(r -> r.setPriorityScore(priorityService.calculate(r)));
        queue.sort((a, b) -> Integer.compare(
                b.getPriorityScore(), a.getPriorityScore()));
        return queue;
    }

    public AllocationResult dispatch(Long resourceId, String actor) {
        List<EmergencyRequest> queue = getQueue(resourceId);

        if (queue.isEmpty()) {
            throw new RuntimeException("No pending requests for this resource");
        }

        EmergencyRequest top = queue.get(0);

        try {
            var allocation = allocationService.allocate(top.getId(), actor);
            return new AllocationResult(
                    "ALLOCATED", allocation.getId(), top.getId());
        } catch (RuntimeException insufficientStock) {
            return new AllocationResult(
                    "WAITING_OR_PREEMPTION_REQUIRED", null, top.getId());
        }
    }

    public record AllocationResult(
            String result,
            Long allocationId,
            Long requestId) {}
}
