package com.cras.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events hub: the browser keeps one HTTP connection open and the server pushes
 * events (new request, allocation, preemption, autopilot ticks ...) the moment they happen.
 *
 * Events published inside a database transaction are delivered only AFTER that transaction
 * commits, so a client that reacts by re-fetching never reads data from before the change.
 */
@Service
public class RealtimeService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);
    private static final long TIMEOUT_MS = 30 * 60_000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, "hello", Map.of("clients", emitters.size()));
        return emitter;
    }

    public int clients() {
        return emitters.size();
    }

    public void publish(String event, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcast(event, payload);
                }
            });
        } else {
            broadcast(event, payload);
        }
    }

    private void broadcast(String event, Object payload) {
        log.debug("[realtime] {} -> {}", event, payload);
        for (SseEmitter e : emitters) send(e, event, payload);
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("event", event);
        body.put("payload", payload);
        body.put("ts", Instant.now().toString());
        try {
            emitter.send(SseEmitter.event().name(event).data(body, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(emitter);          // client went away
        }
    }

    /** Keeps proxies from closing idle connections and prunes dead clients. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        if (!emitters.isEmpty()) broadcast("ping", Map.of("clients", emitters.size()));
    }
}
