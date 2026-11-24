package com.cras.service;

import com.cras.entity.EmergencyRequest;
import com.cras.entity.RequestStatus;
import com.cras.os.SchedulerSimulator;
import com.cras.os.SchedulerSimulator.Job;
import com.cras.os.SchedulerSimulator.PolicyResult;
import com.cras.repository.RequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

/**
 * Builds a workload (the live PENDING queue, a seeded mixed sample, or a starvation stream) and replays it
 * under every scheduling policy so they can be compared on identical input.
 *
 * Model for live requests: 1 tick = 1 minute; arrival = minutes since the oldest pending request
 * (capped at 12 h); burst = estimated handling time = 3 + quantity/25 minutes (clamped 2..40);
 * priority = the stored priority score; critical = severity >= 8.
 */
@Service
public class SimulationService {

    private final RequestRepository requests;

    public SimulationService(RequestRepository requests) {
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> simulate(String source, int n, long seed, int quantum, int agingStep, int starveLimit) {
        quantum = clamp(quantum, 1, 30);
        agingStep = clamp(agingStep, 1, 120);
        starveLimit = clamp(starveLimit, 5, 1000);
        List<Job> jobs = switch (source.toLowerCase()) {
            case "live" -> liveJobs();
            case "stream" -> streamJobs(clamp(n, 4, 30), seed);
            default -> sampleJobs(clamp(n, 4, 30), seed);
        };

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", jobs.isEmpty() ? "live (queue is empty)" : source.toLowerCase());
        out.put("quantum", quantum);
        out.put("agingStep", agingStep);
        out.put("starveLimit", starveLimit);
        out.put("jobs", jobs);
        List<PolicyResult> results = jobs.isEmpty() ? List.of()
                : SchedulerSimulator.runAll(jobs, quantum, agingStep, starveLimit);
        out.put("results", results);
        return out;
    }

    private List<Job> liveJobs() {
        List<EmergencyRequest> pending = new ArrayList<>(
                requests.findByStatusOrderByPriorityScoreDescCreatedAtAsc(RequestStatus.PENDING));
        if (pending.isEmpty()) return List.of();
        pending.sort(Comparator.comparing(EmergencyRequest::getCreatedAt));
        var origin = pending.get(0).getCreatedAt();
        List<Job> jobs = new ArrayList<>();
        for (EmergencyRequest r : pending.stream().limit(30).toList()) {
            int arrival = (int) Math.min(720, Math.max(0, Duration.between(origin, r.getCreatedAt()).toMinutes()));
            int burst = clamp(3 + r.getQuantity() / 25, 2, 40);
            jobs.add(new Job(r.getId(), "#" + r.getId() + " " + r.getResource().getName(), arrival, burst,
                    r.getPriorityScore(), r.getSeverity() >= 8));
        }
        return jobs;
    }

    /** Deterministic sample: one long low-priority job first (convoy / starvation), then mixed work. */
    private List<Job> sampleJobs(int n, long seed) {
        Random rnd = new Random(seed);
        List<Job> jobs = new ArrayList<>();
        jobs.add(new Job(1, "S1 Bulk water", 0, 26, 14, false));
        int t = 1;
        for (int i = 2; i <= n; i++) {
            t += rnd.nextInt(4);
            int severity = 1 + rnd.nextInt(10);
            int burst = 2 + rnd.nextInt(14);
            int priority = Math.min(100, severity * 7 + rnd.nextInt(30));
            jobs.add(new Job(i, "S" + i + (severity >= 8 ? " critical" : ""), t, burst, priority, severity >= 8));
        }
        return jobs;
    }

    /**
     * Starvation scenario: one low-priority job arrives first, then urgent short jobs keep arriving
     * every 2 minutes, each needing 2 minutes (the server is 100 % busy). Under static priority the
     * early job is never chosen until the stream ends; aging lets its waiting time buy priority.
     */
    private List<Job> streamJobs(int n, long seed) {
        Random rnd = new Random(seed);
        List<Job> jobs = new ArrayList<>();
        jobs.add(new Job(1, "S1 Low-priority batch", 0, 8, 25, false));
        for (int i = 2; i <= n; i++) {
            int priority = 30 + rnd.nextInt(20);
            jobs.add(new Job(i, "S" + i + " urgent", 2 * (i - 1), 2, priority, priority >= 45));
        }
        return jobs;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
