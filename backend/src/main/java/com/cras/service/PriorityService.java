package com.cras.service;

import com.cras.entity.EmergencyRequest;
import com.cras.entity.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Dynamic priority score, 0-100. One class, used everywhere (request creation, queue view,
 * allocation, preemption), and the single place where the AI signal plugs in.
 *
 * cras.priority.mode = phase2 (default, unchanged behaviour)
 *   35 Severity + 20 Affected + 20 Scarcity(user) + 15 LocationRisk + Aging(0-10 over 2 h)
 *
 * cras.priority.mode = phase3
 *   30 Severity + 15 Affected + 15 Scarcity(LIVE from stock) + 15 LocationRisk
 *   + Aging(0-15 over 4 h) + AI(0-10)
 *   AI term = 10 * shortageRisk * confidence * severity.
 *   Multiplying by severity matters: the shortage risk is the same for every request in one
 *   resource queue, so adding it as a constant would never change their ORDER. Scaling it by
 *   severity makes a predicted shortage favour the most urgent requests.
 *   If the AI service is off, down or unsure, the AI term is 0 and the rest still works.
 */
@Service
public class PriorityService {

    private final AiPredictionClient ai;
    private final boolean phase3;

    public PriorityService(AiPredictionClient ai,
                           @Value("${cras.priority.mode:phase2}") String mode) {
        this.ai = ai;
        this.phase3 = "phase3".equalsIgnoreCase(mode);
    }

    public int calculate(EmergencyRequest r) {
        return phase3 ? calculatePhase3(r) : calculatePhase2(r);
    }

    private int calculatePhase2(EmergencyRequest r) {
        double severity = r.getSeverity() / 10.0;
        double affected = Math.min(r.getAffectedPeople(), 100) / 100.0;
        double scarcity = r.getScarcity() / 10.0;
        double locationRisk = locationRisk01(r);
        return (int) Math.round(severity * 35 + affected * 20 + scarcity * 20
                + locationRisk * 15 + agingPoints(r));
    }

    private int calculatePhase3(EmergencyRequest r) {
        double severity = r.getSeverity() / 10.0;
        double affected = Math.min(r.getAffectedPeople(), 100) / 100.0;
        double scarcity = liveScarcity01(r);
        double locationRisk = locationRisk01(r);
        double aging = Math.min(waitingMinutes(r) / 240.0, 1.0) * 15;

        double aiTerm = 0;
        AiPredictionClient.AiSignal s = ai.forResource(r.getResource());
        if (s.available() && s.confidence() >= 0.3) {
            aiTerm = 10 * s.risk() * s.confidence() * severity;
        }
        return (int) Math.round(severity * 30 + affected * 15 + scarcity * 15
                + locationRisk * 15 + aging + aiTerm);
    }

    private double locationRisk01(EmergencyRequest r) {
        int risk = r.getLocation() == null ? 5 : r.getLocation().getRiskLevel();
        return Math.max(1, Math.min(10, risk)) / 10.0;
    }

    /** Scarcity computed from the real stock, so it cannot be mistyped or exaggerated. */
    private double liveScarcity01(EmergencyRequest r) {
        Resource res = r.getResource();
        if (res != null && res.getCapacity() != null && res.getCapacity() > 0) {
            double ratio = res.getAvailableQuantity() / (double) res.getCapacity();
            return Math.max(0.0, Math.min(1.0, 1.0 - ratio));
        }
        return r.getScarcity() / 10.0;       // fallback: the user-entered value
    }

    /** Phase II aging (used by the Phase II formula and by the UI). */
    public int agingPoints(EmergencyRequest r) {
        long minutes = waitingMinutes(r);
        return (int) Math.round(Math.min(minutes / 120.0, 1.0) * 10);
    }

    /** Aging points currently contributing to the score under the ACTIVE priority mode. */
    public double agingBoost(EmergencyRequest r) {
        double minutes = waitingMinutes(r);
        return phase3 ? Math.min(minutes / 240.0, 1.0) * 15 : Math.min(minutes / 120.0, 1.0) * 10;
    }

    /** A request waiting this long (minutes) is flagged as starving in the UI. */
    public static final long STARVATION_MINUTES = 240;

    public long waitingMinutes(EmergencyRequest r) {
        return Math.max(0, Duration.between(r.getCreatedAt(), LocalDateTime.now()).toMinutes());
    }
}
