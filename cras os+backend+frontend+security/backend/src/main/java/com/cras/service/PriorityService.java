package com.cras.service;

import com.cras.entity.EmergencyRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class PriorityService {

    /*
     * Non-AI dynamic priority score: 0-100.
     *
     * Severity       35%
     * Affected       20%
     * Scarcity       20%
     * Location risk  15%
     * Aging          10%
     *
     * AI prediction/confidence is intentionally NOT included.
     */
    public int calculate(EmergencyRequest r) {
        double severity = r.getSeverity() / 10.0;
        double affected = Math.min(r.getAffectedPeople(), 100) / 100.0;
        double scarcity = r.getScarcity() / 10.0;

        int risk = r.getLocation() == null ? 5 : r.getLocation().getRiskLevel();
        double locationRisk = Math.max(1, Math.min(10, risk)) / 10.0;

        return (int) Math.round(
                severity * 35 +
                affected * 20 +
                scarcity * 20 +
                locationRisk * 15 +
                agingPoints(r)
        );
    }

    public int agingPoints(EmergencyRequest r) {
        long minutes = Math.max(0,
                Duration.between(r.getCreatedAt(), LocalDateTime.now()).toMinutes());

        return (int) Math.round(Math.min(minutes / 120.0, 1.0) * 10);
    }

    public long waitingMinutes(EmergencyRequest r) {
        return Math.max(0,
                Duration.between(r.getCreatedAt(), LocalDateTime.now()).toMinutes());
    }
}
