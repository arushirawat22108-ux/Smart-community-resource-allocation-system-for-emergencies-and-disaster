package com.cras.service;

import com.cras.dto.RequestCreate;
import com.cras.dto.RequestView;
import com.cras.entity.EmergencyRequest;
import com.cras.entity.RequestStatus;
import com.cras.repository.LocationRepository;
import com.cras.repository.RequestRepository;
import com.cras.repository.ResourceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class RequestService {

    private final RequestRepository requests;
    private final ResourceRepository resources;
    private final LocationRepository locations;
    private final PriorityService priority;
    private final AuditService audit;
    private final RealtimeService realtime;
    private final JdbcTemplate jdbc;
    private final SeverityService severityService;

    public RequestService(RequestRepository requests, ResourceRepository resources, LocationRepository locations,
                          PriorityService priority, AuditService audit, RealtimeService realtime, JdbcTemplate jdbc,
                          SeverityService severityService) {
        this.requests = requests;
        this.resources = resources;
        this.locations = locations;
        this.priority = priority;
        this.audit = audit;
        this.realtime = realtime;
        this.jdbc = jdbc;
        this.severityService = severityService;
    }

    // ---------------------------------------------------------------- mapping

    public RequestView toView(EmergencyRequest r, String viewerEmail, boolean privileged) {
        boolean mine = viewerEmail != null && viewerEmail.equalsIgnoreCase(r.getRequesterEmail());
        long waited = priority.waitingMinutes(r);
        String requester = (privileged || mine) ? r.getRequesterEmail() : mask(r.getRequesterEmail());
        return new RequestView(r.getId(), r.getResource().getId(), r.getResource().getName(), r.getResource().getUnit(),
                r.getLocation() == null ? null : r.getLocation().getId(),
                r.getLocation() == null ? null : r.getLocation().getName(),
                r.getQuantity(), r.getSeverity(), r.getAffectedPeople(), r.getStatus().name(),
                r.getPriorityScore(), r.getCreatedAt(), waited,
                Math.round(priority.agingBoost(r) * 10.0) / 10.0,
                r.getStatus() == RequestStatus.PENDING && waited >= PriorityService.STARVATION_MINUTES,
                requester, mine);
    }

    public List<RequestView> toViews(List<EmergencyRequest> list, String viewer, boolean privileged) {
        return list.stream().map(r -> toView(r, viewer, privileged)).toList();
    }

    private static String mask(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<RequestView> list(String viewer, boolean privileged) {
        List<EmergencyRequest> all = requests.findAll();
        if (!privileged) all = all.stream().filter(r -> viewer.equalsIgnoreCase(r.getRequesterEmail())).toList();
        return all.stream()
                .sorted(Comparator.comparing(EmergencyRequest::getCreatedAt).reversed())
                .limit(300)
                .map(r -> toView(r, viewer, privileged)).toList();
    }

    @Transactional(readOnly = true)
    public List<RequestView> pending(String viewer, boolean privileged) {
        return toViews(requests.findByStatusOrderByPriorityScoreDescCreatedAtAsc(RequestStatus.PENDING), viewer, privileged);
    }

    // ---------------------------------------------------------------- commands

    /**
     * Creates a request. With an Idempotency-Key the same logical request can be sent many times
     * (double click, network retry) and is created exactly ONCE.
     *
     * The key is claimed by inserting into a table with a PRIMARY KEY first thing in the transaction.
     * A concurrent duplicate blocks on that unique index until the first transaction commits, then
     * fails with a duplicate-key error and simply returns the request the first one created.
     */
    @Transactional
    public RequestView create(RequestCreate body, String actor, String idemKey) {
        boolean replay = false;
        if (idemKey != null && !idemKey.isBlank()) {
            if (idemKey.length() > 80) throw new IllegalArgumentException("Idempotency-Key too long (max 80)");
            try {
                jdbc.update("INSERT INTO request_idempotency (idem_key, actor_email) VALUES (?, ?)", idemKey, actor);
            } catch (DuplicateKeyException dup) {
                replay = true;
            }
            if (replay) {
                List<Long> ids = jdbc.queryForList(
                        "SELECT request_id FROM request_idempotency WHERE idem_key = ? AND actor_email = ? FOR UPDATE",
                        Long.class, idemKey, actor);
                if (!ids.isEmpty() && ids.get(0) != null) {
                    EmergencyRequest existing = requests.findById(ids.get(0)).orElseThrow();
                    return toView(existing, actor, false);
                }
                throw new IllegalStateException("This Idempotency-Key was already used by another request");
            }
        }
        var resource = resources.findById(body.resourceId())
                .orElseThrow(() -> new NoSuchElementException("Resource not found"));
        EmergencyRequest r = new EmergencyRequest();
        r.setResource(resource);
        if (body.locationId() != null) {
            r.setLocation(locations.findById(body.locationId()).orElse(null));
        }
        r.setRequesterEmail(actor);
        r.setQuantity(body.quantity());
        SeverityService.Estimate est = severityService.estimate(resource, r.getLocation(),
                Boolean.TRUE.equals(body.medicalNeed()), Boolean.TRUE.equals(body.vulnerablePeople()),
                Boolean.TRUE.equals(body.noCleanWater()));
        r.setSeverity(est.severity());                 // computed on the server, never typed by the user
        r.setAffectedPeople(body.affectedPeople());
        r.setScarcity(est.scarcity());
        r.setStatus(RequestStatus.PENDING);
        r.setPriorityScore(priority.calculate(r));
        EmergencyRequest saved = requests.save(r);
        if (idemKey != null && !idemKey.isBlank()) {
            jdbc.update("UPDATE request_idempotency SET request_id = ? WHERE idem_key = ?", saved.getId(), idemKey);
        }
        audit.log(actor, "REQUEST_CREATED", "Request " + saved.getId() + ": " + saved.getQuantity() + " "
                + resource.getUnit() + " of " + resource.getName() + ", severity " + saved.getSeverity() + " (" + String.join("; ", est.reasons()) + ")");
        realtime.publish("REQUEST_CREATED", Map.of("id", saved.getId(), "severity", saved.getSeverity()));
        return toView(saved, actor, false);
    }

    /** Preview shown on the form before submitting. Saves nothing. */
    @Transactional(readOnly = true)
    public SeverityService.Estimate estimate(RequestCreate body) {
        var resource = resources.findById(body.resourceId())
                .orElseThrow(() -> new NoSuchElementException("Resource not found"));
        var location = body.locationId() == null ? null : locations.findById(body.locationId()).orElse(null);
        return severityService.estimate(resource, location, Boolean.TRUE.equals(body.medicalNeed()),
                Boolean.TRUE.equals(body.vulnerablePeople()), Boolean.TRUE.equals(body.noCleanWater()));
    }

    /** A manager corrects a wrong estimate. The change is written to the audit chain. */
    @Transactional
    public RequestView overrideSeverity(Long id, int value, String actor) {
        if (value < 1 || value > 10) throw new IllegalArgumentException("Severity must be between 1 and 10");
        EmergencyRequest r = requests.findById(id).orElseThrow(() -> new NoSuchElementException("Request not found"));
        int before = r.getSeverity();
        r.setSeverity(value);
        r.setPriorityScore(priority.calculate(r));
        requests.save(r);
        audit.log(actor, "SEVERITY_OVERRIDE", "Request " + id + ": severity " + before + " -> " + value);
        realtime.publish("REQUEST_CREATED", Map.of("id", id, "severity", value));
        return toView(r, actor, true);
    }

    /** Owner or manager may cancel a request that is still waiting. */
    @Transactional
    public RequestView cancel(Long id, String actor, boolean privileged) {
        EmergencyRequest r = requests.findById(id).orElseThrow(() -> new NoSuchElementException("Request not found"));
        if (!privileged && !actor.equalsIgnoreCase(r.getRequesterEmail())) {
            throw new org.springframework.security.access.AccessDeniedException("Not your request");
        }
        if (r.getStatus() != RequestStatus.PENDING) {
            throw new IllegalStateException("Only a PENDING request can be cancelled (this one is " + r.getStatus() + ")");
        }
        r.setStatus(RequestStatus.CANCELLED);
        requests.save(r);
        audit.log(actor, "REQUEST_CANCELLED", "Request " + id + " cancelled");
        realtime.publish("REQUEST_CANCELLED", Map.of("id", id));
        return toView(r, actor, privileged);
    }

    /** State history written by the trg_requests_history trigger. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(Long id, String actor, boolean privileged) {
        EmergencyRequest r = requests.findById(id).orElseThrow(() -> new NoSuchElementException("Request not found"));
        if (!privileged && !actor.equalsIgnoreCase(r.getRequesterEmail())) {
            throw new org.springframework.security.access.AccessDeniedException("Not your request");
        }
        return jdbc.queryForList("SELECT old_status AS fromStatus, new_status AS toStatus, "
                + "DATE_FORMAT(changed_at, '%Y-%m-%d %H:%i:%s') AS at "
                + "FROM request_events WHERE request_id = ? ORDER BY id", id);
    }
}
