# RemoteAccess — APK Build & Dashboard

## What this is
Backend (Node/Express) + React/Vite dashboard + Android client. Backend runs on port 5000 (HTTP/SSE) and 6000 (TCP for devices). MongoDB for persistence, Redis optional.

## Workflows
- **Backend Server**: `cd backend && npm install && npm run build && node server.js`
- **APK Build**: `bash build.sh --worker` (polls backend for build jobs)

## Key code paths
- `backend/server.js` — main API, SSE, device TCP server.
- `backend/models/User.js` — user model with trial + paid subscription fields.
- `backend/routes/userAuth.js` — user signup/login/me.
- `backend/middleware/auth.js` — admin/user JWT middleware (server.js also defines `requireAdmin`, `requireUserOrAdmin`, `requireActiveSubscription`).
- `react-dashboard/src/components/UserDashboard.jsx` — user view; renders `<PaywallOverlay>` when locked.
- `react-dashboard/src/components/PaywallOverlay.jsx` — "Buy us a coffee" lock UI.
- `react-dashboard/src/components/SettingsTab.jsx` — admin sees the NOWPayments webhook URL + IPN secret config.
- `react-dashboard/src/hooks/useTcpStream.js` — falls back to `user_token` if no `admin_token`; surfaces 402 from `/api/commands` as a `subscription:locked` event.

## Trial + paid subscription
- Every user gets a 7-day free trial set in the User pre-save hook (`trialEndDate`).
- After expiry, `POST /api/commands` returns **402 Payment Required** with a `paywall` payload; the dashboard renders the lock screen instead of `<DeviceControl>`.
- Payment unlocks 30 days at a time via `paidUntil`. `User.isTrialActive()` returns true if either `paidUntil` or `trialEndDate` is in the future.

## Payment endpoints
- `GET  /api/payment/me` — current sub status + personalised payment URL (auth: user or admin).
- `POST /api/payment/webhook/nowpayments` — IPN endpoint. Verifies `x-nowpayments-sig` as `HMAC-SHA512(JSON-with-recursively-sorted-keys, ipnSecret)`. On `finished`/`confirmed`/`partially_paid`, locates the user by `order_id` (= our Mongo `_id`) or by `customer_email`, then extends `paidUntil` by 30 days. Idempotent on `payment_id`.
- `GET  /api/admin/payment` — returns webhook URL + secret status (admin).
- `POST /api/admin/payment` — set IPN secret / payment URL / price / duration (admin).
- `POST /api/admin/users/:id/grant-month` — admin manually credits N days (default 30).
- `POST /api/admin/users/:id/revoke-paid` — admin clears paid window.

## NOWPayments setup (admin)
1. Open the dashboard → **Settings → NOWPayments Webhook** card.
2. Copy the **Webhook URL** shown there.
3. In NOWPayments → Account → Store Settings, paste the URL into **IPN Callback URL**.
4. Copy the **IPN Secret** from the same NOWPayments page back into the dashboard and Save.
5. The default payment link (`iid=5745424570&paymentId=4699655886`) is pre-filled and editable. The dashboard appends `order_id=<userId>` and `customer_email=<email>` so the webhook can attribute payments.

Optional env vars (used at boot, overridable at runtime via the admin UI):
- `NOWPAYMENTS_IPN_SECRET`
- `NOWPAYMENTS_PAYMENT_URL`
- `NOWPAYMENTS_PRICE_USD` (default 25)
- `NOWPAYMENTS_EXTEND_DAYS` (default 30)

## Auth model recap
- Admin: hex tokens in `global._adminTokens` (24h TTL).
- User: JWT (`getJwtSecret()` from `backend/jwtSecret.js`), stored in localStorage as `user_token`.
- `requireUserOrAdmin` accepts either; `requireActiveSubscription` then enforces the trial/paid gate (admins always pass).

## Build pipeline note
`build.sh` was previously SIGPIPE-killed by `python3 - <<PYEOF` in `send_logs`. Fixed by writing the helper to a file and exec'ing `python3 -u <file>`. Build is now stable end-to-end (~60s for both APKs).
