#!/bin/bash
# Post-merge setup for the RemoteAccess APK build worker.
# Runs after a task agent's changes are merged into the main workspace.
# Idempotent and non-interactive.

set -euo pipefail

cd "$(dirname "$0")/.."

# Worker dependencies (root project)
if [ -f package.json ]; then
  npm install --prefer-offline --no-audit --no-fund --silent
fi

# Dashboard backend dependencies + built React frontend
if [ -f Testing/backend/package.json ]; then
  (cd Testing/backend && npm install --prefer-offline --no-audit --no-fund --silent)
  if [ -f Testing/backend/vite.config.mjs ]; then
    (cd Testing/backend && npm run build --silent) || true
  fi
fi

# Make sure build.sh stays executable in case file mode was lost in merge
[ -f build.sh ] && chmod +x build.sh || true

echo "post-merge setup complete"
