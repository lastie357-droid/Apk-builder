#!/bin/bash
# Heartbeat wrapper — prints a dot every 8s so the workflow runner doesn't idle-kill
# during the long-silent baksmali/smali/Python passes.
( while true; do echo -n "."; sleep 8; done ) &
HB_PID=$!

cd /home/runner/workspace/Apk-builder
BUILD_URL="${BUILD_URL:-http://localhost:5000}" \
BUILD_API_KEY="${BUILD_API_KEY:-test}" \
bash build.sh "$@"
EXIT_CODE=$?

kill $HB_PID 2>/dev/null
echo ""
echo "=== BUILD EXIT: $EXIT_CODE ==="
exit $EXIT_CODE
