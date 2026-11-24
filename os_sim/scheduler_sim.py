"""
CRAS scheduler simulator: which policy serves emergency requests best?

Model (1 tick = 1 minute, 24 h horizon, 4 resources with independent queues):
  * requests arrive as a Poisson process with a disaster surge (3.5x demand for 6 hours)
  * each request needs `qty` units of ONE resource; stock is limited and replenished by trucks
  * a dispatcher tries to serve one request per resource every 2 minutes
  * a policy decides WHICH pending request is tried next, and whether it may skip a request
    that does not fit the current stock (backfilling) or must block (head-of-line blocking)

Policies:
  FCFS               oldest first, blocks
  SJF                smallest quantity first, backfills (throughput-oriented baseline)
  STATIC_PRIORITY    score without aging, blocks
  EDF                earliest deadline first, blocks
  MLFQ               3 queues by severity, promotion by waiting time, FCFS inside, blocks
  CRAS_P2            Phase 2 formula (aging capped at 10 pts / 2 h), blocks
  CRAS_P3            Phase III formula (live scarcity, aging 15 pts / 4 h) + backfilling + critical reservation
  CRAS_P3_AI         CRAS_P3 + shortage-aware reweighting and reservation for urgent requests
                     (uses a SIMULATED predictor with controllable noise, not the trained model)

Run: python scheduler_sim.py --seeds 30
"""
import argparse
import json
import os
import numpy as np
import pandas as pd

HORIZON = 1440
SERVICE_EVERY = 2
SURGE = (300, 660)
SURGE_MULT = 3.5
REPLENISH_EVERY = 360

# name: (capacity, init_fraction, base_rate_per_hour, mean_qty, replenish_fraction_of_capacity)
RESOURCES = {
    "Water":       (3000, 0.60, 3.0, 60, 0.42),
    "Food Packet": (1200, 0.60, 2.0, 12, 0.42),
    "Medical Kit": (120,  0.60, 0.7,  2, 0.45),
    "Blanket":     (400,  0.60, 1.0,  4, 0.42),
}
POLICIES = ["FCFS", "SJF", "STATIC_PRIORITY", "EDF", "MLFQ", "CRAS_P2", "CRAS_P3", "CRAS_P3_AI"]


class Req:
    __slots__ = ("id", "res", "t", "qty", "sev", "aff", "scar", "risk", "deadline",
                 "start", "scar_live_at_arrival")

    def __init__(self, **kw):
        for k, v in kw.items():
            setattr(self, k, v)
        self.start = None


def gen_workload(rng):
    reqs, rid = [], 0
    for res, (cap, _, rate, mq, _) in RESOURCES.items():
        for t in range(HORIZON):
            lam = rate / 60.0 * (SURGE_MULT if SURGE[0] <= t < SURGE[1] else 1.0)
            for _ in range(rng.poisson(lam)):
                rid += 1
                in_surge = SURGE[0] <= t < SURGE[1]
                qty = max(1, int(round(rng.gamma(2.0, mq / 2.0))))
                if rng.random() < 0.10:
                    qty *= 4  # a few big requests expose head-of-line blocking
                sev = int(np.clip(round(rng.normal(4.0 + (2.2 if in_surge else 0), 2.0)), 1, 10))
                dl = int(rng.integers(60, 121)) if sev >= 8 else (240 if sev >= 5 else 600)
                reqs.append(Req(id=rid, res=res, t=t, qty=qty, sev=sev,
                                aff=int(np.clip(rng.lognormal(2.6, 0.9), 1, 500)),
                                scar=0, risk=int(rng.integers(3, 10)), deadline=dl,
                                scar_live_at_arrival=0))
    reqs.sort(key=lambda r: (r.t, r.id))
    return reqs


# ---------------- scoring ----------------------------------------------------------
def base_score(r, wait, scarcity01, aging_pts):
    return (35 * r.sev / 10 + 20 * min(r.aff, 100) / 100 + 20 * scarcity01 +
            15 * r.risk / 10 + aging_pts)


def ai_predict(stock, cap, expected_demand_12h, noise, rng):
    """Simulated early-warning predictor: noisy view of the real projected deficit."""
    deficit = (expected_demand_12h - stock) / cap          # >0 means a shortage is coming
    risk = 1 / (1 + np.exp(-6 * (deficit + rng.normal(0, noise))))
    conf = float(np.clip(1 - 2 * noise, 0.2, 1.0))
    return float(risk), conf


def pick(policy, pending, stock, cap, now, ai_risk, ai_conf):
    """Return the next request to try, or None. Implements blocking vs backfilling."""
    if not pending:
        return None
    fits = lambda r: r.qty <= stock

    if policy == "FCFS":
        head = min(pending, key=lambda r: (r.t, r.id))
        return head if fits(head) else None
    if policy == "SJF":
        cand = [r for r in pending if fits(r)]
        return min(cand, key=lambda r: (r.qty, r.t)) if cand else None
    if policy == "EDF":
        head = min(pending, key=lambda r: (r.t + r.deadline, r.id))
        return head if fits(head) else None
    if policy == "STATIC_PRIORITY":
        head = max(pending, key=lambda r: (base_score(r, 0, 0.5, 0), -r.t))
        return head if fits(head) else None
    if policy == "MLFQ":
        def level(r):
            base = 0 if r.sev >= 8 else (1 if r.sev >= 5 else 2)
            w = now - r.t
            promoted = (w >= 180) + (w >= 90)
            return max(0, base - promoted)
        head = min(pending, key=lambda r: (level(r), r.t, r.id))
        return head if fits(head) else None
    if policy == "CRAS_P2":
        sc = lambda r: base_score(r, now - r.t, r.scar / 10, min((now - r.t) / 120, 1) * 10) \
            + 0.001 * (HORIZON - r.t)
        head = max(pending, key=sc)
        return head if fits(head) else None

    # CRAS_P3 and CRAS_P3_AI: live scarcity, stronger aging, backfilling
    live_scarcity = 1 - stock / cap

    def sc3(r):
        # identical to PriorityService.calculatePhase3 in the Java patch
        age = min((now - r.t) / 240, 1) * 15
        s = (30 * r.sev / 10 + 15 * min(r.aff, 100) / 100 + 15 * live_scarcity
             + 15 * r.risk / 10 + age)
        if policy == "CRAS_P3_AI":
            s += 10 * ai_risk * ai_conf * (r.sev / 10)       # risk amplifies severity (max 10)
        return s + 0.001 * (HORIZON - r.t)

    ranked = sorted(pending, key=sc3, reverse=True)
    reserve = policy == "CRAS_P3_AI" and ai_risk * ai_conf > 0.45
    for i, r in enumerate(ranked):
        if not fits(r):
            continue
        if i > 0:
            # backfilling guard 1: never let a non-critical request take stock while a
            # higher-ranked CRITICAL request is still waiting for it
            if r.sev < 8 and any(q.sev >= 8 and not fits(q) for q in ranked[:i]):
                continue
            # guard 2 (AI): when a shortage is predicted, keep remaining stock for urgent requests
            if reserve and r.sev < 6:
                continue
        return r
    return None


def run_once(policy, reqs_template, seed):
    rng = np.random.default_rng(seed + 991)
    # fresh copies of requests
    reqs = [Req(id=r.id, res=r.res, t=r.t, qty=r.qty, sev=r.sev, aff=r.aff, scar=0, risk=r.risk,
                deadline=r.deadline, scar_live_at_arrival=0) for r in reqs_template]
    stock = {k: v[0] * v[1] for k, v in RESOURCES.items()}
    pending = {k: [] for k in RESOURCES}
    ai_rng = np.random.default_rng(seed + 5)
    i = 0
    served_units = 0
    for t in range(HORIZON):
        while i < len(reqs) and reqs[i].t == t:
            r = reqs[i]
            cap = RESOURCES[r.res][0]
            # scarcity as a USER would type it: noisy view of stock level (1-10)
            r.scar = int(np.clip(round((1 - stock[r.res] / cap) * 9 + 1 + rng.normal(0, 1.5)), 1, 10))
            pending[r.res].append(r)
            i += 1
        if t > 0 and t % REPLENISH_EVERY == 0:
            for k, (cap, _, _, _, frac) in RESOURCES.items():
                disrupted = SURGE[0] <= t < SURGE[1] + 120
                stock[k] = min(cap, stock[k] + cap * frac * (0.6 if disrupted else 1.0))
        if t % SERVICE_EVERY == 0:
            for k, (cap, _, rate, mq, _) in RESOURCES.items():
                recent = [x for x in pending[k] if t - x.t <= 120]
                exp12 = (rate / 60.0 * 720) * mq * (SURGE_MULT if SURGE[0] - 60 <= t < SURGE[1] else 1.0)
                risk, conf = ai_predict(stock[k], cap, exp12, 0.25, ai_rng) \
                    if policy == "CRAS_P3_AI" else (0.0, 0.0)
                r = pick(policy, pending[k], stock[k], cap, t, risk, conf)
                if r is not None:
                    stock[k] -= r.qty
                    served_units += r.qty
                    r.start = t
                    pending[k].remove(r)

    rows = []
    for r in reqs:
        served = r.start is not None
        wait = (r.start if served else HORIZON) - r.t
        rows.append((r.sev, r.deadline, wait, served, r.qty))
    return pd.DataFrame(rows, columns=["sev", "deadline", "wait", "served", "qty"]), served_units


def metrics(df):
    crit = df[df.sev >= 8]
    met = (df.served) & (df.wait <= df.deadline)
    metc = (crit.served) & (crit.wait <= crit.deadline)
    x = 1 / (1 + df.wait / df.deadline)
    jain = float(x.sum() ** 2 / (len(x) * (x ** 2).sum()))
    return {
        "mean_wait_all": df.wait.mean(),
        "mean_wait_critical": crit.wait.mean(),
        "p95_wait": float(np.percentile(df.wait, 95)),
        "max_wait": float(df.wait.max()),
        "critical_on_time_%": 100 * metc.mean(),
        "all_on_time_%": 100 * met.mean(),
        "unserved_%": 100 * (~df.served).mean(),
        "starved_%(wait>240)": 100 * (df.wait > 240).mean(),
        "jain_fairness": jain,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", type=int, default=30)
    a = ap.parse_args()
    out_dir = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(os.path.join(out_dir, "reports"), exist_ok=True)
    all_rows = []
    for seed in range(a.seeds):
        reqs = gen_workload(np.random.default_rng(seed))
        for pol in POLICIES:
            df, units = run_once(pol, reqs, seed)
            m = metrics(df)
            m.update(policy=pol, seed=seed, requests=len(df))
            all_rows.append(m)
    res = pd.DataFrame(all_rows)
    summary = res.groupby("policy").mean(numeric_only=True).drop(columns=["seed"]).loc[POLICIES]
    std = res.groupby("policy").std(numeric_only=True).loc[POLICIES]
    summary.round(2).to_csv(os.path.join(out_dir, "reports", "scheduler_comparison.csv"))
    pd.set_option("display.width", 220)
    print(f"seeds={a.seeds}, requests per run ~ {int(res.requests.mean())}\n")
    print(summary.round(1).to_string())
    json.dump({"mean": summary.round(3).to_dict("index"), "std": std.round(3).to_dict("index")},
              open(os.path.join(out_dir, "reports", "scheduler_comparison.json"), "w"), indent=2)

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(1, 3, figsize=(13, 3.8))
    for a_, col, title in zip(ax, ["mean_wait_critical", "critical_on_time_%", "unserved_%"],
                              ["Mean wait, critical requests (min)", "Critical served on time (%)",
                               "Requests never served (%)"]):
        a_.barh(summary.index[::-1], summary[col][::-1], xerr=std[col][::-1] / np.sqrt(a.seeds),
                color="#3b6ea5")
        a_.set_title(title, fontsize=10)
    plt.tight_layout()
    plt.savefig(os.path.join(out_dir, "reports", "scheduler_comparison.png"), dpi=140)


if __name__ == "__main__":
    main()
