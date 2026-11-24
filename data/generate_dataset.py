"""
CRAS synthetic historical dataset generator.

Produces two files:
  data/cras_hourly_panel.csv   one row per (hour, resource): features + shortage labels
  data/cras_requests.csv       one row per emergency request (with injected fake requests)

IMPORTANT (be honest in the viva): this is SIMULATED data. Real request-level relief
logs are not public. The simulator is shaped by real-world structure: monsoon rainfall,
disaster events (flood / landslide / earthquake) that cause demand surges, daily rhythm,
and truck replenishment that gets disrupted during disasters.

Optional: pass --emdat path/to/emdat.xlsx to calibrate how often events occur and how
large they are, using the real EM-DAT public table (free registration at public.emdat.be).

Usage:
  python generate_dataset.py --days 184 --seed 7
  python generate_dataset.py --emdat emdat_india.xlsx
"""
import argparse
import numpy as np
import pandas as pd

RESOURCES = {
    # name: (unit, capacity, base_rate/hour, mean_qty, rain_sens, event_sens, replenish_fill)
    "Water":       ("litre", 14000, 3.0, 60, 0.9, 1.4, 0.90),
    "Food Packet": ("packet", 2200, 2.0, 12, 0.6, 1.2, 0.85),
    "Medical Kit": ("kit",     220, 0.5,  2, 0.4, 2.2, 0.70),
    "Blanket":     ("piece",   450, 1.0,  4, 0.7, 0.9, 0.80),
}

# Illustrative Dehradun-area zones (coordinates approximate, for demo only)
ZONES = [
    ("Rajpur Road",   30.3753, 78.0745, 4),
    ("Clock Tower",   30.3245, 78.0420, 5),
    ("Prem Nagar",    30.3320, 77.9650, 6),
    ("Sahastradhara", 30.3790, 78.1230, 8),
    ("Raipur",        30.3040, 78.0910, 7),
]

EVENT_TYPES = ["flood", "landslide", "earthquake"]


def emdat_event_params(path):
    """Read an EM-DAT export and derive events/year and a size scale. Optional."""
    df = pd.read_excel(path)
    cols = {c.lower(): c for c in df.columns}
    year_col = cols.get("start year")
    aff_col = cols.get("total affected")
    n_years = max(1, df[year_col].nunique()) if year_col else 1
    events_per_year = len(df) / n_years
    if aff_col is not None:
        q = df[aff_col].dropna()
        scale = float(np.clip(np.log10(q.median() + 1) / 5.0, 0.5, 1.6)) if len(q) else 1.0
    else:
        scale = 1.0
    return events_per_year, scale


def simulate(days, seed, events_per_year=None, size_scale=1.0):
    rng = np.random.default_rng(seed)
    start = pd.Timestamp("2024-05-01 00:00")
    hours = days * 24
    ts = start + pd.to_timedelta(np.arange(hours), unit="h")

    # --- weather: monsoon-shaped hourly rainfall (mm) -------------------------
    month = ts.month.values
    monsoon = np.isin(month, [6, 7, 8, 9]).astype(float)
    wet_prob = 0.04 + 0.22 * monsoon
    is_wet = rng.random(hours) < wet_prob
    rain = np.where(is_wet, rng.gamma(1.6, 3.5 + 6 * monsoon, hours), 0.0)

    # --- disaster events ------------------------------------------------------
    lam_per_day = (events_per_year / 365.0) if events_per_year else 1 / 16.0
    n_events = rng.poisson(lam_per_day * days * 1.0)
    event_intensity = np.zeros(hours)
    event_type = np.array([""] * hours, dtype=object)
    event_age = np.full(hours, 999.0)
    events = []
    n_ev = max(n_events, 3)
    for i in range(n_ev):
        # jittered grid: events are spread over the whole year, so train/val/test periods
        # each contain disasters (a purely random draw can leave the test period event-free)
        t0 = 24 + int((i + rng.random()) * (hours - 120) / n_ev)
        etype = rng.choice(EVENT_TYPES, p=[0.5, 0.3, 0.2])
        inten = float(np.clip(rng.gamma(2.2, 1.0) * size_scale, 1.0, 5.0))
        dur = int(rng.integers(12, 60))
        events.append((t0, etype, inten, dur))
        if etype == "flood":  # floods come with heavy rain
            rain[t0:t0 + dur] += rng.gamma(2.0, 5 * inten, min(dur, hours - t0))
        for k in range(min(dur + 36, hours - t0)):  # surge decays after the event ends
            decay = 1.0 if k < dur else np.exp(-(k - dur) / 14.0)
            val = inten * decay
            if val > event_intensity[t0 + k]:
                event_intensity[t0 + k] = val
                event_type[t0 + k] = etype
                event_age[t0 + k] = k

    rain_1h = rain
    rain_6h = pd.Series(rain).rolling(6, min_periods=1).sum().values

    hour = ts.hour.values
    diurnal = 0.55 + 0.9 * np.exp(-((hour - 9) ** 2) / 18) + 0.7 * np.exp(-((hour - 19) ** 2) / 14)

    # --- per resource simulation ---------------------------------------------
    panel_rows, req_rows = [], []
    req_id = 0
    user_pool = 3000
    for rname, (unit, cap, base, mean_qty, rain_s, ev_s, fill) in RESOURCES.items():
        stock = float(cap)
        last_replenish = 0
        unmet_hist, demand_hist, counts, sev_hist, risk_hist = [], [], [], [], []
        stocks, unmets, demands, hrs_since_rep, pending_q = [], [], [], [], []
        backlog = 0.0
        for t in range(hours):
            surge = 1 + ev_s * event_intensity[t] + rain_s * min(rain_6h[t], 60) / 20.0
            lam = base * diurnal[t] * surge
            n = rng.poisson(lam)
            qty = np.maximum(1, rng.gamma(2.0, mean_qty / 2.0, n)).round().astype(int)
            demand = int(qty.sum())

            # requests (for the request-level file)
            zi = rng.integers(0, len(ZONES), n)
            base_sev = 3.5 + 1.1 * event_intensity[t] + rng.normal(0, 1.6, n)
            sev = np.clip(np.round(base_sev), 1, 10).astype(int)
            affected = np.clip(np.round(rng.lognormal(2.6 + 0.12 * event_intensity[t], 0.9, n)), 1, 500).astype(int)
            stock_ratio = stock / cap
            scarcity = np.clip(np.round((1 - stock_ratio) * 9 + 1 + rng.normal(0, 1.2, n)), 1, 10).astype(int)
            for j in range(n):
                fake = rng.random() < 0.015
                s_, a_, q_ = int(sev[j]), int(affected[j]), int(qty[j])
                u = int(rng.integers(0, user_pool))
                if fake:
                    s_ = 10
                    a_ = int(rng.integers(300, 500))
                    q_ = int(q_ * rng.integers(6, 15))
                    u = int(rng.integers(0, 25))  # a few accounts spam
                req_id += 1
                req_rows.append((req_id, ts[t], rname, ZONES[zi[j]][0], ZONES[zi[j]][3],
                                 q_, s_, a_, int(scarcity[j]), u, int(fake)))
                if fake:
                    demand += q_ - int(qty[j])

            # stock dynamics: serve demand, track unmet
            served = min(stock, demand)
            unmet = demand - served
            stock -= served

            # truck replenishment every 24h (disrupted during disasters)
            hrs_rep = t - last_replenish
            if hrs_rep >= 24:
                disruption = 0.35 if event_intensity[t] > 2.5 else 1.0
                if rng.random() < 0.88 * disruption + 0.05:
                    stock = min(cap, stock + fill * (cap - stock) * rng.uniform(0.6, 1.0))
                last_replenish = t

            stocks.append(stock); unmets.append(unmet); demands.append(demand)
            hrs_since_rep.append(t - last_replenish)
            counts.append(n)
            sev_hist.append(float(sev.mean()) if n else np.nan)
            risk_hist.append(float(np.mean([ZONES[z][3] for z in zi])) if n else np.nan)
            backlog = max(0.0, backlog * 0.85 + n - 3.0)  # dispatcher capacity ~3/hour
            pending_q.append(backlog)

        s = pd.DataFrame({"stock": stocks, "unmet": unmets, "demand": demands,
                          "hrs_since_rep": hrs_since_rep, "count": counts,
                          "avg_sev": sev_hist, "avg_risk": risk_hist, "pending": pending_q})
        # features use PAST data only
        c = s["count"]
        feat = pd.DataFrame({
            "timestamp": ts,
            "resource": rname,
            "hour": hour,
            "dow": ts.dayofweek.values,
            "month": month,
            "rain_1h": rain_1h,
            "rain_6h": rain_6h,
            "event_intensity": event_intensity,
            "hours_since_event_start": np.minimum(event_age, 200),
            "req_rate_1h": c.shift(0).values,
            "req_rate_6h": c.rolling(6, min_periods=1).sum().values,
            "req_rate_24h": c.rolling(24, min_periods=1).sum().values,
            "units_demanded_6h": s["demand"].rolling(6, min_periods=1).sum().values,
            "avg_severity_6h": s["avg_sev"].rolling(6, min_periods=1).mean().fillna(4.0).values,
            "avg_risk_6h": s["avg_risk"].rolling(6, min_periods=1).mean().fillna(5.0).values,
            "pending_count": s["pending"].values,
            "stock": s["stock"].values,
            "stock_ratio": (s["stock"] / cap).values,
            "hours_since_replenish": s["hrs_since_rep"].values,
        })
        # labels look FORWARD (t+1 .. t+12)
        fut_unmet = sum(s["unmet"].shift(-k).fillna(0) for k in range(1, 13))
        fut_demand = sum(s["demand"].shift(-k).fillna(0) for k in range(1, 13))
        feat["demand_next_12h"] = fut_demand.values
        feat["unmet_next_12h"] = fut_unmet.values
        feat["shortage_next_12h"] = (fut_unmet.values > 0).astype(int)
        panel_rows.append(feat)

    panel = pd.concat(panel_rows, ignore_index=True).iloc[:-0 or None]
    panel = panel[panel["timestamp"] < ts[-1] - pd.Timedelta(hours=12)]  # drop incomplete label tail
    reqs = pd.DataFrame(req_rows, columns=[
        "request_id", "created_at", "resource", "zone", "zone_risk",
        "quantity", "severity", "affected_people", "scarcity", "user_id", "is_fake"])
    # per-user requests in the previous hour (feature for anomaly detection)
    reqs = reqs.sort_values("created_at").reset_index(drop=True)
    reqs["hour_bucket"] = reqs["created_at"].dt.floor("h")
    reqs["user_reqs_same_hour"] = reqs.groupby(["user_id", "hour_bucket"])["request_id"].transform("count")
    reqs = reqs.drop(columns=["hour_bucket"])
    return panel, reqs, events


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=365)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--emdat", type=str, default=None)
    ap.add_argument("--out", type=str, default=".")
    a = ap.parse_args()

    epy, scale = (None, 1.0)
    if a.emdat:
        epy, scale = emdat_event_params(a.emdat)
        print(f"EM-DAT calibration: {epy:.1f} events/yr, size scale {scale:.2f}")

    panel, reqs, events = simulate(a.days, a.seed, epy, scale)
    panel.to_csv(f"{a.out}/cras_hourly_panel.csv", index=False)
    reqs.to_csv(f"{a.out}/cras_requests.csv", index=False)
    print(f"panel rows: {len(panel):,}  shortage rate: {panel['shortage_next_12h'].mean():.1%}")
    print(f"request rows: {len(reqs):,}  fake share: {reqs['is_fake'].mean():.2%}")
    print(f"disaster events simulated: {len(events)}")
    print(panel.groupby('resource')['shortage_next_12h'].mean().round(3).to_string())


if __name__ == "__main__":
    main()
