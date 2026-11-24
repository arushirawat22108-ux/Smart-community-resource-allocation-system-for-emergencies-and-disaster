package com.cras.service;

import com.cras.entity.Resource;
import com.cras.repository.RequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls the Python AI service (ai/serve.py) for a shortage-risk signal.
 *
 * Design rules:
 *  - OFF by default (cras.ai.enabled=false): the system behaves exactly like Phase II.
 *  - Short timeout and a small cache, so a slow or dead AI service never slows allocation.
 *  - Any failure returns AiSignal.none(): the caller falls back to the rule-based formula.
 */
@Component
public class AiPredictionClient {

    public record AiSignal(double risk, double confidence, boolean available) {
        public static AiSignal none() { return new AiSignal(0, 0, false); }
    }

    private record Cached(AiSignal signal, long atMillis) {}

    private final boolean enabled;
    private final String baseUrl;
    private final long cacheMillis;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestRepository requestRepository;
    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();

    public AiPredictionClient(RequestRepository requestRepository,
                              @Value("${cras.ai.enabled:false}") boolean enabled,
                              @Value("${cras.ai.url:http://localhost:8001}") String baseUrl,
                              @Value("${cras.ai.cache-seconds:60}") long cacheSeconds) {
        this.requestRepository = requestRepository;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.cacheMillis = cacheSeconds * 1000L;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    }

    public AiSignal forResource(Resource resource) {
        if (!enabled || resource == null || resource.getId() == null) {
            return AiSignal.none();
        }
        long now = System.currentTimeMillis();
        Cached hit = cache.get(resource.getId());
        if (hit != null && now - hit.atMillis() < cacheMillis) {
            return hit.signal();
        }
        AiSignal fresh = fetch(resource);
        // cache failures briefly too, so a dead service is not called on every request
        cache.put(resource.getId(), new Cached(fresh, fresh.available() ? now : now - cacheMillis + 15_000L));
        return fresh;
    }

    private AiSignal fetch(Resource resource) {
        try {
            LocalDateTime now = LocalDateTime.now();
            int cap = resource.getCapacity() == null || resource.getCapacity() <= 0
                    ? Math.max(1, resource.getAvailableQuantity()) : resource.getCapacity();
            long last6h = requestRepository.countByResourceIdAndCreatedAtAfter(resource.getId(), now.minusHours(6));
            long last24h = requestRepository.countByResourceIdAndCreatedAtAfter(resource.getId(), now.minusHours(24));
            long last1h = requestRepository.countByResourceIdAndCreatedAtAfter(resource.getId(), now.minusHours(1));

            Map<String, Object> body = new HashMap<>();
            body.put("resource", resource.getName());
            body.put("hour", now.getHour());
            body.put("dow", now.getDayOfWeek().getValue() - 1);
            body.put("stock", resource.getAvailableQuantity());
            body.put("stock_ratio", Math.min(1.0, resource.getAvailableQuantity() / (double) cap));
            body.put("req_rate_1h", last1h);
            body.put("req_rate_6h", last6h);
            body.put("req_rate_24h", last24h);

            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/predict"))
                    .timeout(Duration.ofMillis(800))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return AiSignal.none();
            }
            JsonNode json = mapper.readTree(resp.body());
            return new AiSignal(json.path("shortageRisk").asDouble(0),
                                json.path("confidence").asDouble(0), true);
        } catch (Exception e) {            // timeout, connection refused, bad JSON ...
            return AiSignal.none();
        }
    }
}
