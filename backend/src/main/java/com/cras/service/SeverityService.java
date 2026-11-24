package com.cras.service;

import com.cras.entity.Location;
import com.cras.entity.RequestStatus;
import com.cras.entity.Resource;
import com.cras.repository.RequestRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Estimates severity (1-10) from facts the system already knows, plus three optional yes/no answers.
 * The weights are a transparent first guess, NOT validated against real emergencies:
 * tune them in compute() and say so in the viva.
 */
@Service
public class SeverityService {

    public record Estimate(int severity, int scarcity, List<String> reasons) {}

    private final RequestRepository requests;

    public SeverityService(RequestRepository requests) {
        this.requests = requests;
    }

    public Estimate estimate(Resource resource, Location location,
                             boolean medicalNeed, boolean vulnerablePeople, boolean noCleanWater) {
        int capacity = resource.getCapacity() == null || resource.getCapacity() <= 0
                ? Math.max(1, resource.getAvailableQuantity()) : resource.getCapacity();
        double stockRatio = Math.min(1.0, resource.getAvailableQuantity() / (double) capacity);
        int waitingInArea = location == null ? 0
                : (int) requests.countByLocationIdAndStatus(location.getId(), RequestStatus.PENDING);
        return compute(location == null ? -1 : location.getRiskLevel(), stockRatio, resource.getName(),
                waitingInArea, medicalNeed, vulnerablePeople, noCleanWater);
    }

    /** Pure scoring rule (no database): easy to test and to change. */
    public static Estimate compute(int locationRisk, double stockRatio, String resourceName, int waitingInArea,
                                   boolean medicalNeed, boolean vulnerablePeople, boolean noCleanWater) {
        int score = 1;
        List<String> why = new ArrayList<>();
        if (locationRisk > 0) {
            int pts = (int) Math.round(locationRisk * 0.3);              // risk 1-10 -> 0..3 points
            if (pts > 0) { score += pts; why.add("location risk level " + locationRisk + " (+" + pts + ")"); }
        }
        if (stockRatio < 0.2) { score += 2; why.add("stock is critically low (+2)"); }
        else if (stockRatio < 0.5) { score += 1; why.add("stock is below half (+1)"); }
        if (resourceName != null && resourceName.toLowerCase().contains("medic")) {
            score += 2; why.add("medical supplies (+2)");
        }
        if (waitingInArea >= 3) { score += 1; why.add(waitingInArea + " requests already waiting from this area (+1)"); }
        if (medicalNeed) { score += 2; why.add("urgent medical need reported (+2)"); }
        if (vulnerablePeople) { score += 1; why.add("infants, elderly or pregnant people present (+1)"); }
        if (noCleanWater) { score += 2; why.add("no clean water for a day or more (+2)"); }
        int severity = Math.max(1, Math.min(10, score));
        int scarcity = Math.max(1, Math.min(10, (int) Math.round(10 * (1 - stockRatio))));
        if (why.isEmpty()) why.add("no risk factors found: baseline severity");
        return new Estimate(severity, scarcity, why);
    }
}
