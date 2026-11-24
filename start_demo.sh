#!/usr/bin/env bash
# Start AI service + dark-academia frontend for a quick demo
set -e
cd "$(dirname "$0")"

if [ ! -f ai/model/rf_shortage.joblib ]; then
  echo "Model missing — training now..."
  (cd ai && python train_model.py)
fi

echo "Starting AI service on :8001 ..."
python ai/serve.py --port 8001 &
AI_PID=$!
sleep 1

echo "Starting frontend on :5500 ..."
(cd frontend && python -m http.server 5500) &
FE_PID=$!

echo ""
echo "  AI     → http://localhost:8001/health"
echo "  UI     → http://localhost:5500"
echo "  Press Ctrl+C to stop both."
echo ""

trap "kill $AI_PID $FE_PID 2>/dev/null" EXIT
wait
