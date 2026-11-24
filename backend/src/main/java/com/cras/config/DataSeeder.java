package com.cras.config;

import com.cras.entity.*;
import com.cras.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Start-up sequence (one class, so the order is guaranteed):
 *   1. install DBMS objects (triggers, ledger, views ...)
 *   2. seed demo users, resources, locations, relief camps, audit-chain head
 *
 * Demo passwords live in application.properties (cras.seed.*) - the ONLY place to change them.
 * They are re-applied on each start so the database always matches the properties file.
 * Set cras.seed.enabled=false for anything that is not a local demo.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final DbmsBootstrap bootstrap;
    private final UserRepository users;
    private final ResourceRepository resources;
    private final LocationRepository locations;
    private final ReliefCampRepository camps;
    private final CampClaimRepository claims;
    private final AuditChainHeadRepository chainHead;
    private final PasswordEncoder encoder;
    private final boolean enabled;
    private final String adminPassword;
    private final String managerPassword;
    private final String userPassword;

    public DataSeeder(DbmsBootstrap bootstrap, UserRepository users, ResourceRepository resources,
                      LocationRepository locations, ReliefCampRepository camps, CampClaimRepository claims,
                      AuditChainHeadRepository chainHead, PasswordEncoder encoder,
                      @Value("${cras.seed.enabled:true}") boolean enabled,
                      @Value("${cras.seed.admin-password:CRAS_Admin#82Xq!}") String adminPassword,
                      @Value("${cras.seed.manager-password:CRAS_Manager#73Lp!}") String managerPassword,
                      @Value("${cras.seed.user-password:CRAS_User#91Km!}") String userPassword) {
        this.bootstrap = bootstrap;
        this.users = users;
        this.resources = resources;
        this.locations = locations;
        this.camps = camps;
        this.claims = claims;
        this.chainHead = chainHead;
        this.encoder = encoder;
        this.enabled = enabled;
        this.adminPassword = adminPassword;
        this.managerPassword = managerPassword;
        this.userPassword = userPassword;
    }

    @Override
    public void run(String... args) {
        // Seed resources FIRST so that, when the triggers are installed, every row already exists
        // and the bootstrap writes correct OPENING ledger entries for it.
        if (enabled) seedResources();
        bootstrap.install();
        if (!enabled) return;

        upsertUser("admin@cras.local", "Admin User", Role.ADMIN, adminPassword);
        upsertUser("manager@cras.local", "Resource Manager", Role.RESOURCE_MANAGER, managerPassword);
        upsertUser("user@cras.local", "Community User", Role.COMMUNITY_USER, userPassword);

        if (locations.count() == 0) {
            locations.save(location("Dehradun Camp A", 4, 30.3165, 78.0322));
            locations.save(location("Rishikesh Relief Zone", 7, 30.0869, 78.2676));
            locations.save(location("Haridwar Flood Belt", 9, 29.9457, 78.1642));
        }
        if (!chainHead.existsById(1L)) chainHead.save(new AuditChainHead());
        seedCamps();
        log.info("[seed] demo data ready");
    }

    private void seedResources() {
        if (resources.count() == 0) {
            resources.save(new Resource("Water", "litres", 1000));
            resources.save(new Resource("Food Packet", "packs", 600));
            resources.save(new Resource("Medical Kit", "kits", 200));
            resources.save(new Resource("Blanket", "units", 400));
        }
    }

    private void upsertUser(String email, String fullName, Role role, String rawPassword) {
        User u = users.findByEmail(email).orElseGet(() -> new User(email, "", fullName, role));
        u.setPassword(encoder.encode(rawPassword));
        users.save(u);
    }

    private Location location(String name, int risk, double lat, double lon) {
        Location l = new Location(name, risk);
        l.setLatitude(lat);
        l.setLongitude(lon);
        return l;
    }

    /**
     * Banker's algorithm scenario: four camps with declared maximum needs. Their maxima add up to
     * roughly twice the depot capacity, so the system CAN reach unsafe states - that is the point.
     * Each maximum is below total capacity, as the algorithm requires.
     */
    private void seedCamps() {
        if (camps.count() > 0) return;
        Map<String, double[]> plan = new java.util.LinkedHashMap<>();
        //                          Water  Food  Medical Blanket   (fractions of depot capacity)
        plan.put("Camp Dehradun",  new double[]{0.60, 0.50, 0.55, 0.50});
        plan.put("Camp Rishikesh", new double[]{0.45, 0.40, 0.40, 0.35});
        plan.put("Camp Haridwar",  new double[]{0.70, 0.55, 0.60, 0.55});
        plan.put("Camp Mussoorie", new double[]{0.30, 0.25, 0.30, 0.25});
        var res = resources.findAllByOrderByIdAsc();
        for (var e : plan.entrySet()) {
            ReliefCamp camp = camps.save(new ReliefCamp(e.getKey()));
            for (int j = 0; j < res.size() && j < 4; j++) {
                Resource r = res.get(j);
                int cap = r.getCapacity() == null ? r.getAvailableQuantity() : r.getCapacity();
                claims.save(new CampClaim(camp, r, (int) Math.round(cap * e.getValue()[j])));
            }
        }
    }
}
