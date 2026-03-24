#!/bin/bash

LOG_DIR="/tmp/rat-services"
mkdir -p "$LOG_DIR"

echo "Starting FRPC..."
nohup /home/runner/workspace/frp/frpc -c /home/runner/workspace/frp/frpc.toml > "$LOG_DIR/frpc.log" 2>&1 &

sleep 2

echo "Starting Backend..."
nohup node /home/runner/workspace/backend/server.js > "$LOG_DIR/backend.log" 2>&1 &

echo "Starting Frontend..."
nohup npx http-server /home/runner/workspace/frontend -p 3000 > "$LOG_DIR/frontend.log" 2>&1 &

sleep 3

echo "All services started!"
echo "Logs: $LOG_DIR/"
echo ""
echo "Services:"
echo "- Frontend: http://localhost:3000"
echo "- Backend:  http://localhost:5000"
echo "- FRPC:     public.freefrp.org:8000 (connected)"
