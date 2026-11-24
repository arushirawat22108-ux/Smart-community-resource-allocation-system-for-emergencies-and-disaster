"""
Fake / abusive request detection (unsupervised).

Isolation Forest flags requests whose severity, affected people, quantity and per-user
request burst look unusual. Ground truth (is_fake) exists only because the data is simulated;
it is used ONLY to measure how well the unsupervised model does.

Run: python anomaly_detection.py
"""
import json
import os
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.metrics import precision_score, recall_score, roc_auc_score

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    df = pd.read_csv(os.path.join(HERE, "..", "data", "cras_requests.csv"), parse_dates=["created_at"])
    # normalise quantity per resource so a normal water order is not "big" next to a medical kit
    df["qty_ratio"] = df["quantity"] / df.groupby("resource")["quantity"].transform("median")
    feats = ["severity", "affected_people", "qty_ratio", "scarcity", "user_reqs_same_hour"]
    X = df[feats].values
    contamination = 0.015
    iso = IsolationForest(n_estimators=300, contamination=contamination, random_state=42, n_jobs=-1)
    iso.fit(X)
    score = -iso.score_samples(X)          # higher = more anomalous
    pred = iso.predict(X) == -1
    y = df["is_fake"].values
    out = {
        "requests": int(len(df)),
        "fake_in_data": int(y.sum()),
        "flagged": int(pred.sum()),
        "precision": round(float(precision_score(y, pred)), 4),
        "recall": round(float(recall_score(y, pred)), 4),
        "roc_auc": round(float(roc_auc_score(y, score)), 4),
    }
    os.makedirs(os.path.join(HERE, "reports"), exist_ok=True)
    json.dump(out, open(os.path.join(HERE, "reports", "anomaly_metrics.json"), "w"), indent=2)
    print(json.dumps(out, indent=2))
    print("\nHow it would be used in CRAS: flagged requests are not rejected. They are marked "
          "'needs verification' for a manager, and the priority AI term ignores them until verified.")


if __name__ == "__main__":
    main()
