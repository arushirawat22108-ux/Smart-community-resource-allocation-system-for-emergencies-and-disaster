"""Automated tests for the Python half of CRAS Phase III.
Run:  python -m unittest verification.test_python -v     (from the cras_phase3 folder)
or:   python verification/test_python.py
"""
import json
import os
import random
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import HTTPServer

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
for sub in ("data", "ai", "os_sim"):
    sys.path.insert(0, os.path.join(ROOT, sub))

import numpy as np
import pandas as pd
import generate_dataset as gd
import banker
import scheduler_sim as sim
import serve
import train_model as tm


class TestDataset(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel, cls.reqs, cls.events = gd.simulate(days=40, seed=3)

    def test_label_matches_unmet_demand(self):
        p = self.panel
        self.assertTrue(((p["unmet_next_12h"] > 0).astype(int) == p["shortage_next_12h"]).all())

    def test_features_use_only_past_data(self):
        """req_rate_6h must equal the sum of the last 6 hourly counts INCLUDING the current hour only."""
        for res, g in self.panel.groupby("resource"):
            g = g.sort_values("timestamp")
            expected = g["req_rate_1h"].rolling(6, min_periods=1).sum().values
            np.testing.assert_allclose(g["req_rate_6h"].values, expected)

    def test_incomplete_label_tail_removed(self):
        last = self.panel["timestamp"].max()
        first = self.panel["timestamp"].min()
        self.assertGreaterEqual((last - first).days, 38)   # nothing but the final 12 h dropped

    def test_ranges_and_no_missing_values(self):
        p = self.panel
        self.assertTrue(p["stock_ratio"].between(0, 1).all())
        self.assertTrue((p["stock"] >= 0).all())
        feats = [f for f in tm.FEATURES]
        self.assertFalse(p[feats].isna().any().any())

    def test_both_classes_present(self):
        rate = self.panel["shortage_next_12h"].mean()
        self.assertTrue(0.02 < rate < 0.6, rate)

    def test_request_file_has_fakes_and_valid_ranges(self):
        r = self.reqs
        self.assertTrue(r["severity"].between(1, 10).all())
        self.assertTrue(r["scarcity"].between(1, 10).all())
        self.assertTrue((r["quantity"] >= 1).all())
        self.assertGreater(r["is_fake"].sum(), 0)
        self.assertEqual(r["request_id"].is_unique, True)

    def test_reproducible_with_same_seed(self):
        p2, _, _ = gd.simulate(days=40, seed=3)
        pd.testing.assert_frame_equal(self.panel.reset_index(drop=True), p2.reset_index(drop=True))


class TestTrainingArtifacts(unittest.TestCase):
    def test_time_split_is_chronological(self):
        df = tm.load()
        tr, va, te = tm.time_split(df)
        self.assertLess(tr.timestamp.max(), va.timestamp.min())
        self.assertLess(va.timestamp.max(), te.timestamp.min())

    def test_metrics_file_sane_and_beats_baseline(self):
        m = json.load(open(os.path.join(ROOT, "ai", "reports", "metrics.json")))
        by = {r["model"]: r for r in m["classification"]}
        ens = by["Ensemble LR+RF+GB (calibrated)"]
        rule = by["Rule: stock_ratio low"]
        self.assertGreater(ens["pr_auc"], rule["pr_auc"] + 0.2)
        self.assertGreater(ens["roc_auc"], 0.9)
        self.assertLess(ens["brier"], 0.05)
        self.assertLess(m["demand_forecast"]["wape_random_forest"], m["demand_forecast"]["wape_moving_window_baseline"])

    def test_anomaly_detector_finds_injected_fakes(self):
        a = json.load(open(os.path.join(ROOT, "ai", "reports", "anomaly_metrics.json")))
        self.assertGreater(a["recall"], 0.9)
        self.assertGreater(a["precision"], 0.9)


class TestPredictionService(unittest.TestCase):
    def test_output_ranges_and_keys(self):
        out = serve.predict({"resource": "Water", "hour": 10, "stock_ratio": 0.5})
        for k in ("shortageRisk", "confidence", "riskLevel", "model", "perModel"):
            self.assertIn(k, out)
        self.assertTrue(0 <= out["shortageRisk"] <= 1)
        self.assertTrue(0 <= out["confidence"] <= 1)
        self.assertIn(out["riskLevel"], ("LOW", "MEDIUM", "HIGH"))

    def test_risk_higher_in_a_disaster_with_low_stock(self):
        calm = serve.predict({"resource": "Water", "stock_ratio": 0.95, "event_intensity": 0})
        crisis = serve.predict({"resource": "Water", "stock_ratio": 0.30, "event_intensity": 4,
                                "rain_6h": 60, "req_rate_6h": 60, "req_rate_24h": 220,
                                "hours_since_replenish": 20})
        self.assertGreater(crisis["shortageRisk"], calm["shortageRisk"] + 0.2)

    def test_missing_and_unknown_inputs_do_not_crash(self):
        self.assertIn("shortageRisk", serve.predict({}))
        self.assertIn("shortageRisk", serve.predict({"resource": "Unknown Thing", "bogus": 1}))

    def test_http_endpoints(self):
        srv = HTTPServer(("127.0.0.1", 0), serve.Handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{srv.server_port}"
        try:
            self.assertEqual(json.loads(urllib.request.urlopen(base + "/health").read())["status"], "ok")
            req = urllib.request.Request(base + "/predict", data=b'{"resource":"Food Packet","stock_ratio":0.4}',
                                         headers={"Content-Type": "application/json"})
            self.assertIn("shortageRisk", json.loads(urllib.request.urlopen(req).read()))
            bad = urllib.request.Request(base + "/predict", data=b"not json")
            with self.assertRaises(urllib.error.HTTPError) as c:
                urllib.request.urlopen(bad)
            self.assertEqual(c.exception.code, 400)
            with self.assertRaises(urllib.error.HTTPError) as c:
                urllib.request.urlopen(base + "/nope")
            self.assertEqual(c.exception.code, 404)
            self.assertIn("classification", json.loads(urllib.request.urlopen(base + "/model-info").read()))
        finally:
            srv.shutdown()


class TestBanker(unittest.TestCase):
    def test_textbook_state(self):
        mx = [[7, 5, 3], [3, 2, 2], [9, 0, 2], [2, 2, 2], [4, 3, 3]]
        al = [[0, 1, 0], [2, 0, 0], [3, 0, 2], [2, 1, 1], [0, 0, 2]]
        safe, seq = banker.is_safe([3, 3, 2], mx, al)
        self.assertTrue(safe)
        self.assertEqual(seq, [1, 3, 4, 0, 2])

    def test_property_granted_requests_always_leave_a_safe_state(self):
        rnd = random.Random(11)
        for _ in range(300):
            n, m = rnd.randint(2, 5), rnd.randint(1, 4)
            mx = [[rnd.randint(1, 10) for _ in range(m)] for _ in range(n)]
            al = [[rnd.randint(0, mx[i][j]) for j in range(m)] for i in range(n)]
            av = [rnd.randint(0, 8) for _ in range(m)]
            if not banker.is_safe(av, mx, al)[0]:
                continue                                   # property applies to safe starting states
            i = rnd.randrange(n)
            req = [rnd.randint(0, max(0, mx[i][j] - al[i][j])) for j in range(m)]
            ok, _ = banker.request_resources(i, req, av, mx, al)
            self.assertTrue(banker.is_safe(av, mx, al)[0])  # granted or not, state is still safe
            self.assertTrue(all(v >= 0 for v in av))


class TestSchedulerSimulator(unittest.TestCase):
    def test_invariants_for_every_policy(self):
        reqs = sim.gen_workload(np.random.default_rng(5))
        supply_bound = {r: v[0] * v[1] + (sim.HORIZON // sim.REPLENISH_EVERY) * v[0] * v[4]
                        for r, v in sim.RESOURCES.items()}
        total_bound = sum(supply_bound.values())
        for pol in sim.POLICIES:
            df, units = sim.run_once(pol, reqs, 5)
            self.assertEqual(len(df), len(reqs), pol)
            self.assertTrue((df["wait"] >= 0).all(), pol)
            self.assertLessEqual(units, total_bound + 1e-6, f"{pol} served more than the stock that existed")

    def test_deterministic(self):
        reqs = sim.gen_workload(np.random.default_rng(9))
        a = sim.metrics(sim.run_once("CRAS_P3", reqs, 9)[0])
        b = sim.metrics(sim.run_once("CRAS_P3", reqs, 9)[0])
        self.assertEqual(a, b)

    def test_backfilling_policies_serve_more_than_blocking_fcfs(self):
        reqs = sim.gen_workload(np.random.default_rng(2))
        fcfs = sim.metrics(sim.run_once("FCFS", reqs, 2)[0])["unserved_%"]
        sjf = sim.metrics(sim.run_once("SJF", reqs, 2)[0])["unserved_%"]
        self.assertLess(sjf, fcfs)


if __name__ == "__main__":
    unittest.main(verbosity=2)
