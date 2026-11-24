"""
CRAS shortage-risk model.

Task: for a resource at hour t, predict whether demand will go UNMET in the next 6 hours.
Compares: rule baseline, logistic regression, Random Forest, XGBoost (or sklearn boosting if absent).
Serves: calibrated ensemble (LR+RF+GB), confidence = agreement between models and trees.

Run:  python train_model.py            (reads ../data/cras_hourly_panel.csv)
Out:  model/rf_shortage.joblib, reports/metrics.json, reports/*.png
"""
import json
import os
import joblib
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from sklearn.calibration import calibration_curve
from sklearn.ensemble import (HistGradientBoostingClassifier, RandomForestClassifier,
                              RandomForestRegressor)
from sklearn.inspection import permutation_importance
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (average_precision_score, brier_score_loss, f1_score,
                             precision_score, recall_score, roc_auc_score, roc_curve)
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("CRAS_OUT_DIR", HERE)      # where model/ and reports/ are written
SEED = 42
FEATURES = [
    "hour", "dow", "rain_1h", "rain_6h", "event_intensity", "hours_since_event_start",
    "req_rate_1h", "req_rate_6h", "req_rate_24h", "units_demanded_6h",
    "avg_severity_6h", "avg_risk_6h", "pending_count", "stock", "stock_ratio",
    "hours_since_replenish",
]
RESOURCES = ["Water", "Food Packet", "Medical Kit", "Blanket"]


try:                                    # XGBoost is the intended gradient-boosting model
    from xgboost import XGBClassifier
    XGBOOST_USED = True
except Exception:                       # not installed here: fall back to scikit-learn boosting
    XGBClassifier = None
    XGBOOST_USED = False
BOOST_NAME = "XGBoost" if XGBOOST_USED else "Gradient Boosting (sklearn fallback)"


def make_booster():
    if XGBOOST_USED:
        return XGBClassifier(n_estimators=300, max_depth=4, learning_rate=0.05, subsample=0.8,
                             colsample_bytree=0.8, min_child_weight=5, reg_lambda=1.0,
                             tree_method="hist", eval_metric="logloss", n_jobs=-1,
                             random_state=SEED)
    print("!! xgboost is not installed: using scikit-learn HistGradientBoosting instead "
          "(same algorithm family). Run `pip install xgboost` and retrain for the real thing.")
    return HistGradientBoostingClassifier(max_depth=4, learning_rate=0.05, max_iter=200,
                                          random_state=SEED)


def load():
    df = pd.read_csv(os.path.join(HERE, "..", "data", "cras_hourly_panel.csv"),
                     parse_dates=["timestamp"])
    for r in RESOURCES:
        df[f"res_{r}"] = (df["resource"] == r).astype(int)
    return df


def time_split(df):
    ts = np.sort(df["timestamp"].unique())
    t_train = ts[int(len(ts) * 0.70)]
    t_val = ts[int(len(ts) * 0.85)]
    return (df[df.timestamp < t_train], df[(df.timestamp >= t_train) & (df.timestamp < t_val)],
            df[df.timestamp >= t_val])


EARLY_WARNING_MIN_STOCK = 0.20  # only rows where stock is still >20% of capacity (not already empty)


def best_threshold(y, p):
    best_t, best_f = 0.5, -1
    for t in np.linspace(0.05, 0.95, 91):
        f = f1_score(y, p >= t, zero_division=0)
        if f > best_f:
            best_t, best_f = t, f
    return float(best_t)


def evaluate(name, y, p, thr):
    pred = p >= thr
    return {
        "model": name,
        "roc_auc": round(roc_auc_score(y, p), 4),
        "pr_auc": round(average_precision_score(y, p), 4),
        "brier": round(brier_score_loss(y, np.clip(p, 0, 1)), 4),
        "precision": round(precision_score(y, pred, zero_division=0), 4),
        "recall": round(recall_score(y, pred, zero_division=0), 4),
        "f1": round(f1_score(y, pred, zero_division=0), 4),
        "threshold": round(thr, 2),
    }


def tree_confidence(rf, X):
    """Confidence = how strongly the individual trees agree (1 - 2*std of tree probabilities)."""
    per_tree = np.stack([t.predict_proba(X)[:, 1] for t in rf.estimators_], axis=0)
    return np.clip(1.0 - 2.0 * per_tree.std(axis=0), 0.0, 1.0)


def main():
    os.makedirs(os.path.join(OUT, "model"), exist_ok=True)
    os.makedirs(os.path.join(OUT, "reports"), exist_ok=True)
    df = load()
    cols = FEATURES + [f"res_{r}" for r in RESOURCES]
    df = df[df['stock_ratio'] >= EARLY_WARNING_MIN_STOCK]  # early-warning regime
    tr, va, te = time_split(df)
    Xtr, ytr = tr[cols].values, tr["shortage_next_12h"].values
    Xva, yva = va[cols].values, va["shortage_next_12h"].values
    Xte, yte = te[cols].values, te["shortage_next_12h"].values
    print(f"train {len(tr):,}  val {len(va):,}  test {len(te):,}  "
          f"shortage rate train/val/test = {ytr.mean():.1%}/{yva.mean():.1%}/{yte.mean():.1%}")

    results, probs = [], {}

    def logit(p):
        p = np.clip(p, 1e-4, 1 - 1e-4)
        return np.log(p / (1 - p)).reshape(-1, 1)

    def platt(p_va_, y_va_):
        """Platt scaling fitted on the validation period (stable even with few positives)."""
        c = LogisticRegression(C=1.0, max_iter=1000)
        c.fit(logit(p_va_), y_va_)
        return c

    # 1) Rule baseline: low stock => shortage
    p_rule_va = 1 - va["stock_ratio"].values
    p_rule_te = 1 - te["stock_ratio"].values
    results.append(evaluate("Rule: stock_ratio low", yte, p_rule_te, best_threshold(yva, p_rule_va)))
    probs["Rule baseline"] = p_rule_te

    models = {
        "Logistic Regression": make_pipeline(StandardScaler(), LogisticRegression(max_iter=3000)),
        "Random Forest": RandomForestClassifier(n_estimators=300, min_samples_leaf=10,
                                                max_features="sqrt", n_jobs=-1, random_state=SEED),
        BOOST_NAME: make_booster(),
    }
    cal_p_va, cal_p_te, calibrators = {}, {}, {}
    for name, m in models.items():
        m.fit(Xtr, ytr)
        raw_va, raw_te = m.predict_proba(Xva)[:, 1], m.predict_proba(Xte)[:, 1]
        results.append(evaluate(name + " (raw)", yte, raw_te, best_threshold(yva, raw_va)))
        probs[name] = raw_te
        calibrators[name] = platt(raw_va, yva)
        cal_p_va[name] = calibrators[name].predict_proba(logit(raw_va))[:, 1]
        cal_p_te[name] = calibrators[name].predict_proba(logit(raw_te))[:, 1]
    rf = models["Random Forest"]

    # Heterogeneous ensemble: mean of the three calibrated probabilities
    P_va = np.stack([cal_p_va[n] for n in models], axis=1)
    P_te = np.stack([cal_p_te[n] for n in models], axis=1)
    ens_va, ens_te = P_va.mean(axis=1), P_te.mean(axis=1)
    thr = best_threshold(yva, ens_va)
    results.append(evaluate("Ensemble LR+RF+GB (calibrated)", yte, ens_te, thr))
    probs["Ensemble (calibrated)"] = ens_te
    p_te = models["Random Forest"].predict_proba(Xte)[:, 1]          # raw RF for the calibration plot
    p_cal_te = ens_te

    # --- Confidence score: agreement between the three models AND between the forest's trees
    model_spread = P_te.std(axis=1)
    tree_spread = np.stack([t.predict_proba(Xte)[:, 1] for t in rf.estimators_], axis=0).std(axis=0)
    conf_te = np.clip(1.0 - 2.0 * (0.5 * model_spread + 0.5 * tree_spread), 0.0, 1.0)
    pred = p_cal_te >= thr
    correct = (pred == yte.astype(bool))
    q = np.quantile(conf_te, [0, 1 / 3, 2 / 3, 1.0])
    bins = []
    for i, name in enumerate(["low", "medium", "high"]):
        m = (conf_te >= q[i]) & (conf_te <= q[i + 1] if i == 2 else conf_te < q[i + 1])
        bins.append({"confidence_band": name, "n": int(m.sum()),
                     "mean_confidence": round(float(conf_te[m].mean()), 3),
                     "accuracy": round(float(correct[m].mean()), 4),
                     "positives": int(yte[m].sum())})

    # --- Demand regression: next-6h units vs moving-average baseline --------------
    reg_cols = cols
    rfr = RandomForestRegressor(n_estimators=200, min_samples_leaf=5, n_jobs=-1, random_state=SEED)
    rfr.fit(tr[reg_cols].values, tr["demand_next_12h"].values)
    pred_d = rfr.predict(te[reg_cols].values)
    base_d = te["units_demanded_6h"].values  # persistence / moving window baseline
    act = te["demand_next_12h"].values
    wape = lambda a, b: float(np.abs(a - b).sum() / np.abs(a).sum())
    demand = {"wape_random_forest": round(wape(act, pred_d), 4),
              "wape_moving_window_baseline": round(wape(act, base_d), 4)}

    # --- Permutation importance on test (explainability) ------------------------
    imp = permutation_importance(rf, Xte, yte, n_repeats=5, random_state=SEED,
                                 scoring="average_precision", n_jobs=-1)
    imp_df = pd.DataFrame({"feature": cols, "importance": imp.importances_mean}) \
        .sort_values("importance", ascending=False)

    # --- Save -------------------------------------------------------------------
    joblib.dump({"models": models, "calibrators": calibrators, "features": cols,
                 "resources": RESOURCES, "threshold": thr, "version": "ensemble-1"},
                os.path.join(OUT, "model", "rf_shortage.joblib"), compress=3)
    metrics = {"split": {"train": len(tr), "val": len(va), "test": len(te),
                         "shortage_rate_test": round(float(yte.mean()), 4)},
               "classification": results, "confidence_bands": bins, "demand_forecast": demand,
               "top_features": imp_df.head(8).round(4).to_dict("records"),
               "boosting_model": BOOST_NAME, "xgboost_used": XGBOOST_USED}
    json.dump(metrics, open(os.path.join(OUT, "reports", "metrics.json"), "w"), indent=2)

    # --- Plots ------------------------------------------------------------------
    rep = os.path.join(OUT, "reports")
    plt.figure(figsize=(5.5, 4.5))
    for name, p in probs.items():
        fpr, tpr, _ = roc_curve(yte, p)
        plt.plot(fpr, tpr, label=f"{name} ({roc_auc_score(yte, p):.3f})")
    plt.plot([0, 1], [0, 1], "k--", lw=0.8)
    plt.xlabel("False positive rate"); plt.ylabel("True positive rate")
    plt.title("ROC on held-out test period"); plt.legend(fontsize=7); plt.tight_layout()
    plt.savefig(os.path.join(rep, "roc.png"), dpi=140); plt.close()

    plt.figure(figsize=(5, 4.5))
    for name, p in [("Random Forest (raw)", p_te), ("Ensemble (calibrated)", p_cal_te)]:
        fr, mp = calibration_curve(yte, p, n_bins=10, strategy="quantile")
        plt.plot(mp, fr, "o-", label=name)
    plt.plot([0, 1], [0, 1], "k--", lw=0.8)
    plt.xlabel("Predicted probability"); plt.ylabel("Observed frequency")
    plt.title("Calibration (reliability)"); plt.legend(fontsize=8); plt.tight_layout()
    plt.savefig(os.path.join(rep, "calibration.png"), dpi=140); plt.close()

    top = imp_df.head(10).iloc[::-1]
    plt.figure(figsize=(6, 4.2))
    plt.barh(top["feature"], top["importance"], color="#3b6ea5")
    plt.xlabel("Drop in PR-AUC when shuffled"); plt.title("What drives the shortage prediction")
    plt.tight_layout(); plt.savefig(os.path.join(rep, "feature_importance.png"), dpi=140); plt.close()

    plt.figure(figsize=(4.5, 3.8))
    plt.bar([b["confidence_band"] for b in bins], [b["accuracy"] for b in bins], color="#3b6ea5")
    plt.ylim(0.5, 1.0); plt.ylabel("Decision accuracy"); plt.title("Accuracy by confidence band")
    plt.tight_layout(); plt.savefig(os.path.join(rep, "confidence_bands.png"), dpi=140); plt.close()

    print(pd.DataFrame(results).to_string(index=False))
    print("\nConfidence bands:\n", pd.DataFrame(bins).to_string(index=False))
    print("\nDemand forecast (WAPE, lower is better):", demand)
    print("\nTop features:\n", imp_df.head(8).round(4).to_string(index=False))


if __name__ == "__main__":
    main()
