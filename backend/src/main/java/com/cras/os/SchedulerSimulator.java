package com.cras.os;

import java.util.*;

/**
 * Discrete-time CPU-scheduler style simulator applied to emergency requests (1 tick = 1 minute).
 * Pure logic, no framework dependencies. Compares classic policies on the SAME workload:
 * FCFS, SJF, SRTF, preemptive PRIORITY, ROUND_ROBIN and CRAS_AGING (priority + aging).
 */
public final class SchedulerSimulator {

    private SchedulerSimulator() {}

    public record Job(long id, String label, int arrival, int burst, int priority, boolean critical) {}

    public record Segment(long jobId, int start, int end) {}

    public record JobStat(long id, String label, int arrival, int burst, int start,
                          int completion, int waiting, int turnaround, int response, boolean critical) {}

    public record PolicyResult(String policy, String description, List<Segment> gantt, List<JobStat> jobs,
                               double avgWaiting, double avgTurnaround, double avgResponse,
                               double avgCriticalWaiting, int maxWaiting, int starved,
                               int preemptions, int makespan, double fairness) {}

    public static final List<String> POLICIES =
            List.of("FCFS", "SJF", "SRTF", "PRIORITY", "ROUND_ROBIN", "CRAS_AGING");

    public static List<PolicyResult> runAll(List<Job> jobs, int quantum, int agingStep, int starveLimit) {
        List<PolicyResult> out = new ArrayList<>();
        for (String p : POLICIES) out.add(run(p, jobs, quantum, agingStep, starveLimit));
        return out;
    }

    public static PolicyResult run(String policy, List<Job> input, int quantum, int agingStep, int starveLimit) {
        if (quantum < 1) quantum = 1;
        if (agingStep < 1) agingStep = 1;
        List<Job> jobs = new ArrayList<>(input);
        int n = jobs.size();
        int[] rem = new int[n], start = new int[n], comp = new int[n], waited = new int[n];
        for (int i = 0; i < n; i++) {
            rem[i] = jobs.get(i).burst();
            start[i] = -1;
        }
        List<Segment> segs = new ArrayList<>();
        int preemptions = policy.equals("ROUND_ROBIN")
                ? runRoundRobin(jobs, rem, start, comp, segs, quantum)
                : runGeneric(policy, jobs, rem, start, comp, waited, segs, agingStep);
        return summarize(policy, jobs, segs, start, comp, preemptions, starveLimit);
    }

    /** FCFS, SJF, SRTF, PRIORITY, CRAS_AGING: pick one job per tick. */
    private static int runGeneric(String policy, List<Job> jobs, int[] rem, int[] start, int[] comp,
                                  int[] waited, List<Segment> segs, int agingStep) {
        int n = jobs.size(), t = 0, done = 0, current = -1, preemptions = 0;
        while (done < n) {
            int pick = choose(policy, jobs, rem, waited, t, current, agingStep);
            if (pick == -1) { t++; current = -1; continue; }          // CPU idle
            if (current != -1 && pick != current && rem[current] > 0) preemptions++;
            current = pick;
            if (start[pick] < 0) start[pick] = t;
            rem[pick]--;
            addTick(segs, jobs.get(pick).id(), t);
            for (int i = 0; i < n; i++) {                            // everyone else ready is waiting
                if (i != pick && rem[i] > 0 && jobs.get(i).arrival() <= t) waited[i]++;
            }
            t++;
            if (rem[pick] == 0) { comp[pick] = t; done++; current = -1; }
        }
        return preemptions;
    }

    /** Round robin: new arrivals are queued before the job whose slice just expired. */
    private static int runRoundRobin(List<Job> jobs, int[] rem, int[] start, int[] comp,
                                     List<Segment> segs, int quantum) {
        int n = jobs.size(), t = 0, done = 0, current = -1, used = 0, preemptions = 0;
        Deque<Integer> queue = new ArrayDeque<>();
        boolean[] admitted = new boolean[n];
        while (done < n) {
            for (int i = 0; i < n; i++) {
                if (!admitted[i] && jobs.get(i).arrival() <= t) { queue.addLast(i); admitted[i] = true; }
            }
            if (current != -1 && used >= quantum) {                  // slice expired
                queue.addLast(current);
                preemptions++;
                current = -1;
            }
            if (current == -1) {
                if (queue.isEmpty()) { t++; continue; }              // idle
                current = queue.pollFirst();
                used = 0;
            }
            if (start[current] < 0) start[current] = t;
            rem[current]--;
            used++;
            addTick(segs, jobs.get(current).id(), t);
            t++;
            if (rem[current] == 0) { comp[current] = t; done++; current = -1; }
        }
        return preemptions;
    }

    private static int choose(String policy, List<Job> jobs, int[] rem, int[] waited,
                              int t, int current, int agingStep) {
        switch (policy) {
            case "FCFS":
            case "SJF":
                if (current != -1 && rem[current] > 0) return current;       // non-preemptive
                return best(jobs, rem, t, (a, b) -> {
                    if (policy.equals("SJF")) {
                        int c = Integer.compare(jobs.get(a).burst(), jobs.get(b).burst());
                        if (c != 0) return c;
                    }
                    return byArrival(jobs, a, b);
                });
            case "SRTF":
                return best(jobs, rem, t, (a, b) -> {
                    int c = Integer.compare(rem[a], rem[b]);
                    return c != 0 ? c : byArrival(jobs, a, b);
                });
            case "PRIORITY":
                return best(jobs, rem, t, (a, b) -> {
                    int c = Integer.compare(jobs.get(b).priority(), jobs.get(a).priority());
                    return c != 0 ? c : byArrival(jobs, a, b);
                });
            case "CRAS_AGING":
                return best(jobs, rem, t, (a, b) -> {
                    int ea = effective(jobs.get(a).priority(), waited[a], agingStep);
                    int eb = effective(jobs.get(b).priority(), waited[b], agingStep);
                    int c = Integer.compare(eb, ea);
                    return c != 0 ? c : byArrival(jobs, a, b);
                });
            default:
                throw new IllegalArgumentException("Unknown policy " + policy);
        }
    }

    private static int byArrival(List<Job> jobs, int a, int b) {
        int c = Integer.compare(jobs.get(a).arrival(), jobs.get(b).arrival());
        return c != 0 ? c : Integer.compare(a, b);
    }

    /** Aging: +1 effective priority point per agingStep minutes of accumulated waiting (never reset). */
    static int effective(int base, int waited, int agingStep) {
        return base + Math.max(0, waited) / agingStep;
    }

    private static int best(List<Job> jobs, int[] rem, int t, Comparator<Integer> cmp) {
        int best = -1;
        for (int i = 0; i < jobs.size(); i++) {
            if (rem[i] <= 0 || jobs.get(i).arrival() > t) continue;
            if (best == -1 || cmp.compare(i, best) < 0) best = i;
        }
        return best;
    }

    private static void addTick(List<Segment> segs, long id, int t) {
        if (!segs.isEmpty()) {
            Segment last = segs.get(segs.size() - 1);
            if (last.jobId() == id && last.end() == t) {
                segs.set(segs.size() - 1, new Segment(id, last.start(), t + 1));
                return;
            }
        }
        segs.add(new Segment(id, t, t + 1));
    }

    private static PolicyResult summarize(String policy, List<Job> jobs, List<Segment> segs, int[] start,
                                          int[] comp, int preemptions, int starveLimit) {
        int n = jobs.size();
        List<JobStat> stats = new ArrayList<>();
        double sw = 0, st = 0, sr = 0, cw = 0, jainNum = 0, jainDen = 0;
        int crit = 0, maxW = 0, starved = 0, makespan = 0;
        for (int i = 0; i < n; i++) {
            Job j = jobs.get(i);
            int turnaround = comp[i] - j.arrival();
            int waiting = turnaround - j.burst();
            int response = start[i] - j.arrival();
            stats.add(new JobStat(j.id(), j.label(), j.arrival(), j.burst(), start[i], comp[i],
                    waiting, turnaround, response, j.critical()));
            sw += waiting; st += turnaround; sr += response;
            if (j.critical()) { cw += waiting; crit++; }
            maxW = Math.max(maxW, waiting);
            if (waiting > starveLimit) starved++;
            makespan = Math.max(makespan, comp[i]);
            double x = j.burst() / (double) Math.max(1, turnaround);   // normalised service share
            jainNum += x; jainDen += x * x;
        }
        double fairness = n == 0 || jainDen == 0 ? 1.0 : (jainNum * jainNum) / (n * jainDen);
        return new PolicyResult(policy, describe(policy), segs, stats,
                n == 0 ? 0 : round(sw / n), n == 0 ? 0 : round(st / n), n == 0 ? 0 : round(sr / n),
                crit == 0 ? 0 : round(cw / crit), maxW, starved, preemptions, makespan, round(fairness));
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    public static String describe(String policy) {
        return switch (policy) {
            case "FCFS" -> "First come, first served. Simple, but one long job delays everyone (convoy effect).";
            case "SJF" -> "Shortest job first (non-preemptive). Minimises average wait, can starve long jobs.";
            case "SRTF" -> "Shortest remaining time first (preemptive SJF).";
            case "PRIORITY" -> "Preemptive static priority. Urgent work first, low priority may starve.";
            case "ROUND_ROBIN" -> "Equal time slices in turn. Fair, but ignores urgency.";
            case "CRAS_AGING" -> "Preemptive priority plus aging: waiting raises effective priority, preventing starvation.";
            default -> policy;
        };
    }
}
