package com.cras.service;

import com.cras.entity.Resource;
import com.cras.repository.ResourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Autopilot": the scheduler as a background daemon, like a CPU dispatcher loop.
 * Every tick it walks all resources and dispatches the best waiting request
 * (priority + aging + backfilling + critical reservation). Optionally it preempts a lower
 * priority allocation for a critical (severity >= 8) request that cannot otherwise be served.
 *
 * Runs on its own thread, so it genuinely competes with manual clicks for the same locked rows.
 */
@Service
public class AutopilotService {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_PER_RESOURCE = 10;

    private final SchedulerService scheduler;
    private final AllocationService allocation;
    private final ResourceRepository resources;
    private final RealtimeService realtime;
    private final AuditService audit;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean preempt = new AtomicBoolean(true);
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong preemptions = new AtomicLong();
    private final Deque<String> journal = new ArrayDeque<>();
    private volatile String lastActor = "autopilot";

    public AutopilotService(SchedulerService scheduler, AllocationService allocation, ResourceRepository resources,
                            RealtimeService realtime, AuditService audit,
                            @Value("${cras.autopilot.enabled-at-start:false}") boolean startEnabled) {
        this.scheduler = scheduler;
        this.allocation = allocation;
        this.resources = resources;
        this.realtime = realtime;
        this.audit = audit;
        this.enabled.set(startEnabled);
    }

    public void configure(boolean on, boolean allowPreempt, String actor) {
        boolean changed = enabled.getAndSet(on) != on;
        preempt.set(allowPreempt);
        lastActor = actor;
        if (changed) {
            audit.log(actor, on ? "AUTOPILOT_ON" : "AUTOPILOT_OFF", "preemption " + (allowPreempt ? "allowed" : "disabled"));
            note(on ? "Autopilot engaged by " + actor : "Autopilot disengaged by " + actor);
        }
        realtime.publish("AUTOPILOT", status());
    }

    @Scheduled(fixedDelayString = "${cras.autopilot.interval-ms:5000}", initialDelay = 8000)
    public void scheduledTick() {
        if (enabled.get()) tick("autopilot");
    }

    /** One scheduler pass. Also exposed as a manual "step" button for demonstrations. */
    public synchronized Map<String, Object> tick(String actor) {
        long n = ticks.incrementAndGet();
        int served = 0, preempted = 0;
        for (Resource r : resources.findAll()) {
            for (int i = 0; i < MAX_PER_RESOURCE; i++) {
                try {
                    SchedulerService.AllocationResult res = scheduler.dispatch(r.getId(), actor);
                    if (res.allocationId() != null) {
                        served++;
                        dispatched.incrementAndGet();
                        note(r.getName() + ": request #" + res.requestId() + " dispatched (" + res.result() + ")");
                        continue;                                   // try to serve the next one
                    }
                    // Head of queue does not fit. If it is critical, try to preempt for it.
                    if (preempt.get() && res.requestId() != null) {
                        try {
                            allocation.preemptFor(res.requestId(), actor);
                            preempted++;
                            preemptions.incrementAndGet();
                            note(r.getName() + ": critical request #" + res.requestId() + " served by PREEMPTION");
                            continue;
                        } catch (RuntimeException notPossible) {
                            note(r.getName() + ": request #" + res.requestId() + " waits (" + notPossible.getMessage() + ")");
                        }
                    }
                    break;
                } catch (NoSuchElementException emptyQueue) {
                    break;                                          // nothing pending for this resource
                } catch (RuntimeException e) {
                    note(r.getName() + ": " + e.getMessage());
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tick", n);
        out.put("dispatched", served);
        out.put("preempted", preempted);
        if (served > 0 || preempted > 0 || !"autopilot".equals(actor)) realtime.publish("AUTOPILOT_TICK", out);   // stay quiet on idle ticks
        return out;
    }

    private synchronized void note(String msg) {
        journal.addFirst(LocalTime.now().format(CLOCK) + "  " + msg);
        while (journal.size() > 60) journal.removeLast();
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled.get());
        m.put("preemption", preempt.get());
        m.put("ticks", ticks.get());
        m.put("dispatched", dispatched.get());
        m.put("preemptions", preemptions.get());
        m.put("lastActor", lastActor);
        m.put("journal", new ArrayList<>(journal));
        return m;
    }
}
