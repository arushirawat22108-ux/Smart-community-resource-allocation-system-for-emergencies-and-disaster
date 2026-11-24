package com.cras.os;

import java.util.ArrayList;
import java.util.List;

/**
 * Banker's algorithm (deadlock AVOIDANCE) — pure logic, no framework dependencies.
 *
 * Mapping to CRAS: process = relief camp, resource type = Water/Food/Medical/Blanket,
 * Available = live depot stock, Max = declared maximum need, Allocation = units already held.
 * A request is granted only if the resulting state is SAFE: some order exists in which every
 * camp can still receive its full remaining need and finish.
 */
public final class BankerAlgorithm {

    private BankerAlgorithm() {}

    public record Safety(boolean safe, List<Integer> sequence) {}

    public record Decision(boolean granted, String reason, Safety safetyAfter) {}

    /** Safety check. Returns the first safe sequence found (indices of processes). */
    public static Safety isSafe(int[] available, int[][] max, int[][] alloc) {
        int n = max.length, m = available.length;
        int[] work = available.clone();
        boolean[] done = new boolean[n];
        List<Integer> seq = new ArrayList<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < n; i++) {
                if (done[i]) continue;
                boolean fits = true;
                for (int j = 0; j < m; j++) {
                    if (max[i][j] - alloc[i][j] > work[j]) { fits = false; break; }
                }
                if (fits) {
                    for (int j = 0; j < m; j++) work[j] += alloc[i][j];   // camp finishes, returns holdings
                    done[i] = true;
                    seq.add(i);
                    progress = true;
                }
            }
        }
        boolean all = seq.size() == n;
        return new Safety(all, all ? seq : List.of());
    }

    /**
     * Evaluates a request WITHOUT mutating the arrays. The caller applies the grant
     * (inside its database transaction) only if {@code granted} is true.
     */
    public static Decision evaluate(int proc, int[] request, int[] available, int[][] max, int[][] alloc) {
        int m = available.length;
        boolean any = false;
        for (int j = 0; j < m; j++) {
            if (request[j] < 0) return new Decision(false, "Negative quantities are not allowed", null);
            if (request[j] > 0) any = true;
        }
        if (!any) return new Decision(false, "Request is empty", null);
        for (int j = 0; j < m; j++) {
            if (request[j] > max[proc][j] - alloc[proc][j]) {
                return new Decision(false, "Exceeds the camp's declared maximum need (resource #" + j + ")", null);
            }
        }
        for (int j = 0; j < m; j++) {
            if (request[j] > available[j]) {
                return new Decision(false, "Not enough stock right now: camp must wait", null);
            }
        }
        int[] newAvail = new int[m];
        int[][] newAlloc = new int[alloc.length][];
        for (int i = 0; i < alloc.length; i++) newAlloc[i] = alloc[i].clone();
        for (int j = 0; j < m; j++) {
            newAvail[j] = available[j] - request[j];
            newAlloc[proc][j] += request[j];
        }
        Safety s = isSafe(newAvail, max, newAlloc);
        if (!s.safe()) {
            return new Decision(false, "Would leave the system in an UNSAFE state (deadlock possible): must wait", s);
        }
        return new Decision(true, "Granted: the resulting state is safe", s);
    }
}
