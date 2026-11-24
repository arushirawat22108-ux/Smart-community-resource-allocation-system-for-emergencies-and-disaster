"""
CRAS AI inference service (standard library HTTP server, no FastAPI needed).

  GET  /health      -> {"status": "ok", "model": "ensemble-1"}
  GET  /model-info  -> metrics from training
  POST /predict     -> {"shortageRisk": 0..1, "confidence": 0..1, "riskLevel": "LOW|MEDIUM|HIGH", ...}

Spring Boot calls POST /predict with a short timeout; if this service is down or returns
low confidence, the backend falls back to the rule-based priority formula.

Run:  python serve.py --port 8001
Test: curl -s localhost:8001/predict -H 'Content-Type: application/json' \
        -d '{"resource":"Water","hour":14,"stock_ratio":0.4,"event_intensity":3.5}'
"""
import argparse
import json
import os
import numpy as np
import joblib
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
BUNDLE = joblib.load(os.path.join(HERE, "model", "rf_shortage.joblib"))
FEATURES = BUNDLE["features"]
THRESHOLD = BUNDLE["threshold"]
DEFAULTS = {  # used when the caller omits a feature
    "hour": 12, "dow": 2, "rain_1h": 0.0, "rain_6h": 0.0, "event_intensity": 0.0,
    "hours_since_event_start": 200, "req_rate_1h": 3, "req_rate_6h": 18, "req_rate_24h": 72,
    "units_demanded_6h": 300, "avg_severity_6h": 4.0, "avg_risk_6h": 5.0,
    "pending_count": 0, "stock": 0, "stock_ratio": 0.8, "hours_since_replenish": 6,
}


def _logit(p):
    p = np.clip(p, 1e-4, 1 - 1e-4)
    return np.log(p / (1 - p)).reshape(-1, 1)


def predict(payload):
    row = dict(DEFAULTS)
    row.update({k: v for k, v in payload.items() if k in DEFAULTS})
    for r in BUNDLE["resources"]:
        row[f"res_{r}"] = 1 if payload.get("resource") == r else 0
    x = np.array([[float(row.get(f, 0)) for f in FEATURES]])

    probs = []
    for name, model in BUNDLE["models"].items():
        raw = model.predict_proba(x)[:, 1]
        probs.append(BUNDLE["calibrators"][name].predict_proba(_logit(raw))[:, 1][0])
    rf = BUNDLE["models"]["Random Forest"]
    tree_p = np.array([t.predict_proba(x)[0, 1] for t in rf.estimators_])

    risk = float(np.mean(probs))
    confidence = float(np.clip(1.0 - 2.0 * (0.5 * np.std(probs) + 0.5 * tree_p.std()), 0, 1))
    level = "HIGH" if risk >= THRESHOLD else ("MEDIUM" if risk >= THRESHOLD / 2 else "LOW")
    return {"shortageRisk": round(risk, 4), "confidence": round(confidence, 4),
            "riskLevel": level, "threshold": round(THRESHOLD, 3),
            "model": BUNDLE["version"],
            "perModel": {n: round(float(p), 4) for n, p in zip(BUNDLE["models"], probs)}}


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok", "model": BUNDLE["version"]})
        elif self.path == "/model-info":
            with open(os.path.join(HERE, "reports", "metrics.json")) as f:
                self._send(200, json.load(f))
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/predict":
            return self._send(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", 0))
            payload = json.loads(self.rfile.read(n) or b"{}")
            self._send(200, predict(payload))
        except Exception as e:  # noqa: BLE001
            self._send(400, {"error": str(e)})

    def log_message(self, *a):  # quiet
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8001)
    a = ap.parse_args()
    print(f"CRAS AI service on :{a.port}")
    HTTPServer(("0.0.0.0", a.port), Handler).serve_forever()
