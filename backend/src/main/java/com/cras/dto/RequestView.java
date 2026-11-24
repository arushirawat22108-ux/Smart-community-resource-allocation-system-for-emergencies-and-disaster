package com.cras.dto;

import java.time.LocalDateTime;

/**
 * What the API shows about a request. The requester's e-mail is masked unless the viewer is a
 * manager/admin or the owner, so community users cannot read each other's identities.
 */
public record RequestView(
        Long id,
        Long resourceId,
        String resourceName,
        String unit,
        Long locationId,
        String locationName,
        int quantity,
        int severity,
        int affectedPeople,
        String status,
        int priorityScore,
        LocalDateTime createdAt,
        long waitingMinutes,
        double agingBoost,
        boolean starving,
        String requester,
        boolean mine) {}
