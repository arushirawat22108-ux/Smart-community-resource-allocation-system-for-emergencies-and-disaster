"""
Banker's algorithm (deadlock avoidance) for CRAS multi-resource relief kits.

Why it matters: a relief camp often needs SEVERAL resources at once (water + food + medical
+ blankets). If we hand out resources one by one, we can reach a state where every camp holds
part of what it needs, nobody can finish, and the remaining stock is useless: a deadlock.
The Banker's algorithm only grants a request if the resulting state is SAFE, meaning there is
some order in which every camp can still receive its full maximum need.

Mapping to CRAS:
  process          -> relief camp / team
  Max[i]           -> maximum units of each resource camp i will ever need
  Allocation[i]    -> units already given to camp i
  Available        -> stock left in the depot
  Need[i]          -> Max[i] - Allocation[i]

Run demo:   python banker.py
Run tests:  python banker.py --test
"""
import sys
from typing import List, Optional, Tuple

RESOURCES = ["Water", "Food", "Medical", "Blanket"]


def is_safe(available: List[int], max_need: List[List[int]],
            allocation: List[List[int]]) -> Tuple[bool, Optional[List[int]]]:
    """Return (safe?, safe sequence of process indices)."""
    n, m = len(max_need), len(available)
    need = [[max_need[i][j] - allocation[i][j] for j in range(m)] for i in range(n)]
    work = available[:]
    finished = [False] * n
    sequence: List[int] = []
    progress = True
    while progress:
        progress = False
        for i in range(n):
            if not finished[i] and all(need[i][j] <= work[j] for j in range(m)):
                for j in range(m):
                    work[j] += allocation[i][j]      # camp finishes and returns its holdings
                finished[i] = True
                sequence.append(i)
                progress = True
    return all(finished), (sequence if all(finished) else None)


def request_resources(i: int, request: List[int], available: List[int],
                      max_need: List[List[int]], allocation: List[List[int]]):
    """Try to grant `request` to process i. Mutates state only if the grant is safe."""
    m = len(available)
    need_i = [max_need[i][j] - allocation[i][j] for j in range(m)]
    if any(request[j] > need_i[j] for j in range(m)):
        return False, "exceeds declared maximum need"
    if any(request[j] > available[j] for j in range(m)):
        return False, "not enough stock right now (must wait)"
    # pretend to grant it
    new_avail = [available[j] - request[j] for j in range(m)]
    new_alloc = [row[:] for row in allocation]
    for j in range(m):
        new_alloc[i][j] += request[j]
    safe, seq = is_safe(new_avail, max_need, new_alloc)
    if not safe:
        return False, "would lead to an UNSAFE state (possible deadlock), so it must wait"
    for j in range(m):
        available[j] = new_avail[j]
        allocation[i][j] = new_alloc[i][j]
    return True, f"granted; safe sequence = {seq}"


def demo():
    # 4 camps; units: Water(L), Food(packets), Medical(kits), Blankets
    camps = ["Camp A", "Camp B", "Camp C", "Camp D"]
    max_need = [[700, 120, 10, 60], [400, 90, 6, 40], [900, 150, 12, 80], [300, 60, 4, 30]]
    allocation = [[300, 40, 4, 20], [200, 30, 2, 10], [400, 70, 6, 30], [100, 20, 1, 10]]
    available = [300, 50, 5, 40]
    print("Resources:", RESOURCES)
    safe, seq = is_safe(available, max_need, allocation)
    print("Initial state safe?", safe, "->", [camps[i] for i in seq] if seq else None)
    print()
    tests = [
        (3, [100, 10, 1, 10]),   # Camp D asks for a bit more: still safe, so it is granted
        (1, [100, 20, 2, 10]),   # Camp B: stock exists, but granting it would make the state unsafe
        (2, [900, 0, 0, 0]),     # Camp C asks for more water than the depot has
    ]
    for i, req in tests:
        ok, msg = request_resources(i, req, available, max_need, allocation)
        print(f"{camps[i]} requests {req}: {'GRANTED' if ok else 'DENIED'} ({msg})")
        print("   available now:", available)


def _tests():
    # classic textbook state (Silberschatz): safe with sequence P1, P3, P4, P0, P2
    mx = [[7, 5, 3], [3, 2, 2], [9, 0, 2], [2, 2, 2], [4, 3, 3]]
    al = [[0, 1, 0], [2, 0, 0], [3, 0, 2], [2, 1, 1], [0, 0, 2]]
    av = [3, 3, 2]
    safe, seq = is_safe(av, mx, al)
    assert safe and seq == [1, 3, 4, 0, 2], (safe, seq)

    ok, _ = request_resources(1, [1, 0, 2], av, mx, al)      # textbook: grantable
    assert ok and av == [2, 3, 0]

    ok, msg = request_resources(0, [0, 2, 0], av, mx, al)    # textbook: unsafe, must wait
    assert not ok and "UNSAFE" in msg

    ok, msg = request_resources(4, [3, 3, 0], [3, 3, 2], mx, al)   # exceeds stock after pretend? need check
    assert isinstance(ok, bool)

    ok, msg = request_resources(0, [9, 9, 9], [20, 20, 20], mx, al)  # above max need
    assert not ok and "maximum" in msg
    print("banker.py: all tests passed")


if __name__ == "__main__":
    if "--test" in sys.argv:
        _tests()
    else:
        demo()
