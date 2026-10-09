package com.cras.controller;

import com.cras.dto.RequestCreate;
import com.cras.entity.*;
import com.cras.repository.*;
import com.cras.service.PriorityService;
import com.cras.service.RealtimeService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/requests")
@CrossOrigin
public class RequestController {

    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final ResourceRepository resourceRepository;
    private final LocationRepository locationRepository;
    private final PriorityService priorityService;
    private final RealtimeService realtimeService;

    public RequestController(RequestRepository requestRepository,
                             UserRepository userRepository,
                             ResourceRepository resourceRepository,
                             LocationRepository locationRepository,
                             PriorityService priorityService,
                             RealtimeService realtimeService) {
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.resourceRepository = resourceRepository;
        this.locationRepository = locationRepository;
        this.priorityService = priorityService;
        this.realtimeService = realtimeService;
    }

    @GetMapping
    public List<EmergencyRequest> all() {
        return requestRepository.findByStatusOrderByPriorityScoreDescCreatedAtAsc(
                RequestStatus.PENDING);
    }

    @PostMapping
    public EmergencyRequest create(@Valid @RequestBody RequestCreate body,
                                   Authentication authentication) {

        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Resource resource = resourceRepository.findById(body.resourceId())
                .orElseThrow(() -> new RuntimeException("Resource not found"));

        EmergencyRequest r = new EmergencyRequest();
        r.setRequester(user);
        r.setResource(resource);
        r.setQuantity(body.quantity());
        r.setSeverity(body.severity());
        r.setAffectedPeople(body.affectedPeople());
        r.setScarcity(body.scarcity());

        if (body.locationId() != null) {
            r.setLocation(locationRepository.findById(body.locationId())
                    .orElseThrow(() -> new RuntimeException("Location not found")));
        } else {
            r.setLocation(user.getLocation());
        }

        r.setPriorityScore(priorityService.calculate(r));
        EmergencyRequest saved = requestRepository.save(r);

        realtimeService.publish("REQUEST_CREATED", saved.getId());

        return saved;
    }
}
