import com.cras.os.BankerAlgorithm;
import com.cras.os.SchedulerSimulator;
import com.cras.os.SchedulerSimulator.Job;
import com.cras.service.AuditHasher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Framework-free checks of the three pure-logic classes (no Spring, no database needed).
 * Run:  bash verification/run_java_checks.sh
 */
public class AlgorithmChecks {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // ---- scheduling: textbook results ----
        List<Job> t1 = List.of(new Job(1, "P1", 0, 24, 10, false), new Job(2, "P2", 0, 3, 10, false), new Job(3, "P3", 0, 3, 10, false));
        check("FCFS average wait = 17 (24,3,3 all at t=0)", SchedulerSimulator.run("FCFS", t1, 4, 5, 60).avgWaiting() == 17.0);
        check("SJF average wait = 3", SchedulerSimulator.run("SJF", t1, 4, 5, 60).avgWaiting() == 3.0);
        check("Round robin q=4 average wait = 5.67", Math.abs(SchedulerSimulator.run("ROUND_ROBIN", t1, 4, 5, 60).avgWaiting() - 5.67) < 0.01);
        List<Job> t2 = List.of(new Job(1, "A", 0, 8, 1, false), new Job(2, "B", 1, 4, 1, false), new Job(3, "C", 2, 9, 1, false), new Job(4, "D", 3, 5, 1, false));
        check("SRTF average wait = 6.5 (textbook)", SchedulerSimulator.run("SRTF", t2, 4, 5, 60).avgWaiting() == 6.5);
        check("SJF (non-preemptive) average wait = 7.75 (textbook)", SchedulerSimulator.run("SJF", t2, 4, 5, 60).avgWaiting() == 7.75);

        // ---- aging prevents starvation under a continuous urgent stream ----
        List<Job> stream = new ArrayList<>();
        stream.add(new Job(0, "low", 0, 5, 1, false));
        for (int i = 1; i <= 40; i++) stream.add(new Job(i, "hi" + i, i - 1, 1, 10, true));
        int staticWait = SchedulerSimulator.run("PRIORITY", stream, 4, 1, 60).jobs().get(0).waiting();
        int agedWait = SchedulerSimulator.run("CRAS_AGING", stream, 4, 1, 60).jobs().get(0).waiting();
        check("static priority starves the low job (wait " + staticWait + " min)", staticWait >= 35);
        check("aging bounds its wait (wait " + agedWait + " min)", agedWait <= 15);
        for (String p : SchedulerSimulator.POLICIES) {
            var r = SchedulerSimulator.run(p, stream, 4, 2, 60);
            int served = r.gantt().stream().mapToInt(s -> s.end() - s.start()).sum();
            check(p + ": all work is served exactly once", served == stream.stream().mapToInt(Job::burst).sum());
        }

        // ---- Banker's algorithm: Silberschatz textbook example ----
        int[][] max = {{7, 5, 3}, {3, 2, 2}, {9, 0, 2}, {2, 2, 2}, {4, 3, 3}};
        int[][] alloc = {{0, 1, 0}, {2, 0, 0}, {3, 0, 2}, {2, 1, 1}, {0, 0, 2}};
        int[] avail = {3, 3, 2};
        var safe = BankerAlgorithm.isSafe(avail, max, alloc);
        check("initial state is safe with sequence P1,P3,P4,P0,P2", safe.safe() && safe.sequence().equals(List.of(1, 3, 4, 0, 2)));
        check("P1 requests (1,0,2): granted", BankerAlgorithm.evaluate(1, new int[]{1, 0, 2}, avail, max, alloc).granted());
        check("P4 requests (3,3,0): refused (insufficient stock)", !BankerAlgorithm.evaluate(4, new int[]{3, 3, 0}, avail, max, alloc).granted());
        int[][] afterP1 = {{0, 1, 0}, {3, 0, 2}, {3, 0, 2}, {2, 1, 1}, {0, 0, 2}};
        var unsafe = BankerAlgorithm.evaluate(0, new int[]{0, 2, 0}, new int[]{2, 3, 0}, max, afterP1);
        check("P0 requests (0,2,0) after P1: refused as UNSAFE", !unsafe.granted() && unsafe.reason().contains("UNSAFE"));
        check("request above declared maximum is refused", !BankerAlgorithm.evaluate(1, new int[]{9, 0, 0}, avail, max, alloc).granted());

        // ---- audit hash chain primitives ----
        LocalDateTime t = LocalDateTime.of(2026, 11, 2, 10, 0, 0, 123_456_789);
        String h = AuditHasher.hash("GENESIS", "a", "b", "c", t);
        check("hash is 64 hex characters", h.matches("[0-9a-f]{64}"));
        check("hash is deterministic", h.equals(AuditHasher.hash("GENESIS", "a", "b", "c", t)));
        check("sub-millisecond precision ignored (safe across DB round-trip)", h.equals(AuditHasher.hash("GENESIS", "a", "b", "c", t.withNano(123_000_000))));
        check("any changed field changes the hash", !h.equals(AuditHasher.hash("GENESIS", "a", "b", "X", t)) && !h.equals(AuditHasher.hash("OTHER", "a", "b", "c", t)));
        check("field boundaries cannot collide", !AuditHasher.hash("p", "ab", "c", "d", t).equals(AuditHasher.hash("p", "a", "bc", "d", t)));

        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\nFAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
