package com.cras.service;

import com.cras.entity.CampClaim;
import com.cras.entity.ReliefCamp;
import com.cras.entity.Resource;
import com.cras.os.BankerAlgorithm;
import com.cras.repository.CampClaimRepository;
import com.cras.repository.ReliefCampRepository;
import com.cras.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Banker's algorithm over the REAL database.
 *
 *   Available  = live depot stock (resources.available_quantity)
 *   Max        = camp_claims.max_need        (declared maximum need of each camp)
 *   Allocation = camp_claims.allocated       (units a camp currently holds)
 *
 * A grant is applied only if the resulting state is SAFE. The whole operation runs in one
 * transaction that first locks ALL resource rows in ascending id order - a fixed global lock
 * order, which is the classic way to make deadlock between concurrent operations impossible.
 * Ordinary emergency requests draw from the same stock, so the guarantee holds relative to the
 * camps' declared claims (state this limitation when presenting).
 */
@Service
public class BankerService {

    public record ResourceInfo(Long id, String name, String unit, int available) {}

    public record CampRow(Long id, String name, int[] max, int[] allocated, int[] need) {}

    public record State(List<ResourceInfo> resources, List<CampRow> camps, boolean safe, List<String> safeSequence) {}

    public record Outcome(boolean granted, boolean dryRun, String reason, List<String> safeSequence, State state) {}

    private final ResourceRepository resources;
    private final ReliefCampRepository camps;
    private final CampClaimRepository claims;
    private final AuditService audit;
    private final RealtimeService realtime;
    private final DbSession dbSession;

    public BankerService(ResourceRepository resources, ReliefCampRepository camps, CampClaimRepository claims,
                         AuditService audit, RealtimeService realtime, DbSession dbSession) {
        this.resources = resources;
        this.camps = camps;
        this.claims = claims;
        this.audit = audit;
        this.realtime = realtime;
        this.dbSession = dbSession;
    }

    /** Matrices built from entities, kept together so rows/columns always line up. */
    private static final class Matrices {
        final List<Resource> res;
        final List<ReliefCamp> camps;
        final Map<Long, Integer> col = new HashMap<>();
        final Map<Long, Integer> row = new HashMap<>();
        final Map<String, CampClaim> claimAt = new HashMap<>();   // "campId:resourceId"
        final int[] available;
        final int[][] max;
        final int[][] alloc;

        Matrices(List<Resource> res, List<ReliefCamp> camps, List<CampClaim> claims) {
            this.res = res;
            this.camps = camps;
            for (int j = 0; j < res.size(); j++) col.put(res.get(j).getId(), j);
            for (int i = 0; i < camps.size(); i++) row.put(camps.get(i).getId(), i);
            available = new int[res.size()];
            max = new int[camps.size()][res.size()];
            alloc = new int[camps.size()][res.size()];
            refresh(claims);
        }

        void refresh(List<CampClaim> claims) {
            for (int j = 0; j < res.size(); j++) available[j] = res.get(j).getAvailableQuantity();
            for (CampClaim c : claims) {
                Integer i = row.get(c.getCamp().getId());
                Integer j = col.get(c.getResource().getId());
                if (i == null || j == null) continue;
                max[i][j] = c.getMaxNeed();
                alloc[i][j] = c.getAllocated();
                claimAt.put(c.getCamp().getId() + ":" + c.getResource().getId(), c);
            }
        }
    }

    private State toState(Matrices m) {
        List<ResourceInfo> ri = new ArrayList<>();
        for (int j = 0; j < m.res.size(); j++) {
            Resource r = m.res.get(j);
            ri.add(new ResourceInfo(r.getId(), r.getName(), r.getUnit(), m.available[j]));
        }
        List<CampRow> rows = new ArrayList<>();
        for (int i = 0; i < m.camps.size(); i++) {
            int[] need = new int[m.res.size()];
            for (int j = 0; j < need.length; j++) need[j] = m.max[i][j] - m.alloc[i][j];
            rows.add(new CampRow(m.camps.get(i).getId(), m.camps.get(i).getName(),
                    m.max[i].clone(), m.alloc[i].clone(), need));
        }
        BankerAlgorithm.Safety s = BankerAlgorithm.isSafe(m.available, m.max, m.alloc);
        return new State(ri, rows, s.safe(), names(m, s.sequence()));
    }

    private static List<String> names(Matrices m, List<Integer> seq) {
        return seq.stream().map(i -> m.camps.get(i).getName()).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public State state() {
        return toState(new Matrices(resources.findAllByOrderByIdAsc(), camps.findAllByOrderByIdAsc(),
                claims.findEverything()));
    }

    /** Evaluates (dryRun) or applies a multi-resource request for one camp. */
    @Transactional
    public Outcome request(Long campId, Map<Long, Integer> amounts, String actor, boolean dryRun) {
        Matrices m = dryRun
                ? new Matrices(resources.findAllByOrderByIdAsc(), camps.findAllByOrderByIdAsc(), claims.findEverything())
                : new Matrices(resources.lockAllOrdered(), camps.findAllByOrderByIdAsc(), claims.lockAllOrdered());
        Integer proc = m.row.get(campId);
        if (proc == null) throw new NoSuchElementException("Camp not found");

        int[] vec = new int[m.res.size()];
        for (Map.Entry<Long, Integer> e : amounts.entrySet()) {
            Integer j = m.col.get(e.getKey());
            if (j == null) throw new IllegalArgumentException("Unknown resource id " + e.getKey());
            vec[j] = e.getValue() == null ? 0 : e.getValue();
        }

        BankerAlgorithm.Decision d = BankerAlgorithm.evaluate(proc, vec, m.available, m.max, m.alloc);
        String campName = m.camps.get(proc).getName();
        String what = describe(m, vec);

        if (d.granted() && !dryRun) {
            dbSession.tag(actor, "BANKER_GRANT");
            for (int j = 0; j < vec.length; j++) {
                if (vec[j] == 0) continue;
                CampClaim claim = m.claimAt.get(campId + ":" + m.res.get(j).getId());
                claim.setAllocated(claim.getAllocated() + vec[j]);
                claims.save(claim);
                Resource r = m.res.get(j);
                r.setAvailableQuantity(r.getAvailableQuantity() - vec[j]);
                resources.save(r);
            }
            audit.log(actor, "BANKER_GRANT", campName + " granted " + what);
            realtime.publish("BANKER_UPDATED", Map.of("camp", campName, "granted", true));
            m.refresh(claims.findEverything());
        } else if (!d.granted() && !dryRun) {
            audit.log(actor, "BANKER_DENY", campName + " asked " + what + ": " + d.reason());
            realtime.publish("BANKER_UPDATED", Map.of("camp", campName, "granted", false));
        }
        State after = toState(m);
        List<String> seq = d.safetyAfter() == null ? List.of() : names(m, d.safetyAfter().sequence());
        return new Outcome(d.granted(), dryRun, d.reason(), seq, after);
    }

    /** The camp finishes its work: everything it holds goes back to the depot. */
    @Transactional
    public Outcome release(Long campId, String actor) {
        Matrices m = new Matrices(resources.lockAllOrdered(), camps.findAllByOrderByIdAsc(), claims.lockAllOrdered());
        Integer proc = m.row.get(campId);
        if (proc == null) throw new NoSuchElementException("Camp not found");
        String released = releaseRow(m, proc, actor);
        audit.log(actor, "BANKER_RELEASE", m.camps.get(proc).getName() + " finished and returned " + released);
        realtime.publish("BANKER_UPDATED", Map.of("camp", m.camps.get(proc).getName(), "released", true));
        m.refresh(claims.findEverything());
        return new Outcome(true, false, "Camp finished: holdings returned to the depot", List.of(), toState(m));
    }

    /** Scenario reset: every camp returns everything it holds. */
    @Transactional
    public Outcome reset(String actor) {
        Matrices m = new Matrices(resources.lockAllOrdered(), camps.findAllByOrderByIdAsc(), claims.lockAllOrdered());
        for (int i = 0; i < m.camps.size(); i++) releaseRow(m, i, actor);
        audit.log(actor, "BANKER_RESET", "All camps returned their holdings");
        realtime.publish("BANKER_UPDATED", Map.of("reset", true));
        m.refresh(claims.findEverything());
        return new Outcome(true, false, "Scenario reset", List.of(), toState(m));
    }

    private String releaseRow(Matrices m, int i, String actor) {
        dbSession.tag(actor, "BANKER_RELEASE");
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < m.res.size(); j++) {
            CampClaim claim = m.claimAt.get(m.camps.get(i).getId() + ":" + m.res.get(j).getId());
            if (claim == null || claim.getAllocated() == 0) continue;
            Resource r = m.res.get(j);
            r.setAvailableQuantity(r.getAvailableQuantity() + claim.getAllocated());
            resources.save(r);
            if (sb.length() > 0) sb.append(", ");
            sb.append(claim.getAllocated()).append(' ').append(r.getUnit()).append(" of ").append(r.getName());
            claim.setAllocated(0);
            claims.save(claim);
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    private static String describe(Matrices m, int[] vec) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < vec.length; j++) {
            if (vec[j] == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(vec[j]).append(' ').append(m.res.get(j).getUnit()).append(" of ").append(m.res.get(j).getName());
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }
}
