#!/usr/bin/env bash
# Reproduce every Phase III result (Python parts only). Takes a few minutes.
set -e
cd "$(dirname "$0")"
pip install -r requirements.txt
(cd data    && python generate_dataset.py)        # historical dataset (simulated)
(cd ai      && python train_model.py)             # shortage model + metrics + plots
(cd ai      && python anomaly_detection.py)       # fake-request detection
(cd os_sim  && python banker.py --test && python banker.py)
(cd os_sim  && python scheduler_sim.py --seeds 30)  # scheduler comparison
echo "Done. Start the AI service with:  python ai/serve.py --port 8001"
