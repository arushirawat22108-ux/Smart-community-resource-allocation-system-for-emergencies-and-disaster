package com.cras.controller;

import com.cras.entity.RequestStatus;
import com.cras.repository.RequestRepository;
import com.cras.service.BankerService;
import com.cras.service.SimulationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Operating-systems features: scheduler simulator, Banker's algorithm, process-state view. */
@RestController
@RequestMapping("/api/os")
@CrossOrigin
public class OsController {

    public record BankerRequest(Long campId, Map<Long, Integer> amounts, boolean dryRun) {}

    private final SimulationService simulation;
    private final BankerService banker;
    private final RequestRepository requests;

    public OsController(SimulationService simulation, BankerService banker, RequestRepository requests) {
        this.simulation = simulation;
        this.banker = banker;
        this.requests = requests;
    }

    @GetMapping("/simulate")
    public Map<String, Object> simulate(@RequestParam(defaultValue = "sample") String source,
                                        @RequestParam(defaultValue = "14") int n,
                                        @RequestParam(defaultValue = "7") long seed,
                                        @RequestParam(defaultValue = "4") int quantum,
                                        @RequestParam(defaultValue = "5") int aging,
                                        @RequestParam(defaultValue = "60") int starve) {
        return simulation.simulate(source, n, seed, quantum, aging, starve);
    }

    /** Request lifecycle counts, shown against the classic OS process states. */
    @GetMapping("/states")
    public Map<String, Long> states() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (RequestStatus s : RequestStatus.values()) m.put(s.name(), requests.countByStatus(s));
        return m;
    }

    @GetMapping("/banker/state")
    public BankerService.State bankerState() {
        return banker.state();
    }

    @PostMapping("/banker/request")
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    public BankerService.Outcome bankerRequest(@RequestBody BankerRequest body, Authentication auth) {
        return banker.request(body.campId(), body.amounts() == null ? Map.of() : body.amounts(),
                auth.getName(), body.dryRun());
    }

    @PostMapping("/banker/release/{campId}")
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    public BankerService.Outcome bankerRelease(@PathVariable Long campId, Authentication auth) {
        return banker.release(campId, auth.getName());
    }

    @PostMapping("/banker/reset")
    @PreAuthorize("hasAnyRole('RESOURCE_MANAGER','ADMIN')")
    public BankerService.Outcome bankerReset(Authentication auth) {
        return banker.reset(auth.getName());
    }
}
