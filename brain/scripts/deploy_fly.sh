#!/usr/bin/env bash
# Deploys the Emrys brain to Fly.io as one always-on machine with a volume.
#
# Needs in the environment: FLY_API_TOKEN, PERPLEXITY_API_KEY, HORIZON_API_KEY.
# Optional: EMRYS_EMAIL_ADDRESS and EMRYS_EMAIL_PASSWORD (an app password) give Emrys the user's mailbox, with
# EMRYS_EMAIL_IMAP_HOST/_PORT and EMRYS_EMAIL_SMTP_HOST/_PORT for providers without defaults; those set are staged too.
# FLY_LOCAL_BUILD=1 builds with local Docker and pushes instead of using Fly's remote builder.
# Creates the app, volume and device token on the first run; later runs only redeploy.
# The device token (what the phone enters in Settings) is written to $EMRYS_TOKEN_FILE, never printed.
set -euo pipefail

cd "$(dirname "$0")/.."
: "${FLY_API_TOKEN:?set FLY_API_TOKEN}" "${PERPLEXITY_API_KEY:?set PERPLEXITY_API_KEY}" "${HORIZON_API_KEY:?set HORIZON_API_KEY}"
FLY="${FLY:-$(command -v flyctl || command -v fly || echo "$HOME/.fly/bin/flyctl")}"
APP="$(sed -n 's/^app = "\(.*\)"/\1/p' fly.toml)"
REGION="$(sed -n 's/^primary_region = "\(.*\)"/\1/p' fly.toml)"
TOKEN_FILE="${EMRYS_TOKEN_FILE:-$HOME/.emrys-device-token}"

if ! "$FLY" status -a "$APP" >/dev/null 2>&1; then
  "$FLY" apps create "$APP"
fi
if ! "$FLY" volumes list -a "$APP" --json | grep -q '"name": *"rivet_data"'; then
  "$FLY" volumes create rivet_data --size 1 --region "$REGION" -a "$APP" --yes
fi

secrets="$(mktemp)"
trap 'rm -f "$secrets"' EXIT
chmod 600 "$secrets"
printf 'PERPLEXITY_API_KEY=%s\nHORIZON_API_KEY=%s\n' "$PERPLEXITY_API_KEY" "$HORIZON_API_KEY" >"$secrets"
for name in EMRYS_EMAIL_ADDRESS EMRYS_EMAIL_PASSWORD EMRYS_EMAIL_IMAP_HOST EMRYS_EMAIL_IMAP_PORT \
  EMRYS_EMAIL_SMTP_HOST EMRYS_EMAIL_SMTP_PORT; do
  if [ -n "${!name:-}" ]; then printf '%s=%s\n' "$name" "${!name}" >>"$secrets"; fi
done
# An app deployed before the rename keeps its RIVET_DEVICE_TOKEN (the brain reads it as EMRYS_DEVICE_TOKEN), so the
# phone stays paired; a new token is made only when neither is set.
if ! "$FLY" secrets list -a "$APP" --json | grep -qE 'EMRYS_DEVICE_TOKEN|RIVET_DEVICE_TOKEN'; then
  (umask 077 && python3 -c 'import secrets; print(secrets.token_urlsafe(32))' >"$TOKEN_FILE")
  printf 'EMRYS_DEVICE_TOKEN=%s\n' "$(cat "$TOKEN_FILE")" >>"$secrets"
  echo "device token written to $TOKEN_FILE"
fi
"$FLY" secrets import -a "$APP" --stage <"$secrets"

# One machine only: sessions are single-writer and live on the volume.
if [ "${FLY_LOCAL_BUILD:-0}" = "1" ]; then
  # Build with the local Docker daemon and push, for networks that can't reach Fly's remote builder.
  # CA_BUNDLE (optional) is a TLS-intercepting proxy's CA, used only during the build.
  image="registry.fly.io/$APP:deploy-$(date +%Y%m%d%H%M%S)"
  ca=()
  if [ -n "${CA_BUNDLE:-}" ]; then ca=(--secret "id=ca_bundle,src=$CA_BUNDLE"); fi
  docker build --network=host "${ca[@]}" --build-arg HTTPS_PROXY --build-arg https_proxy \
    --build-arg NO_PROXY --build-arg no_proxy -t "$image" .
  printf '%s' "$FLY_API_TOKEN" | docker login registry.fly.io -u x --password-stdin
  docker push "$image"
  "$FLY" deploy -a "$APP" --image "$image" --ha=false --yes
else
  "$FLY" deploy -a "$APP" --remote-only --ha=false --yes
fi
"$FLY" scale count 1 -a "$APP" --yes

for _ in $(seq 1 30); do
  if curl -fsS "https://$APP.fly.dev/healthz" >/dev/null; then
    echo "live: https://$APP.fly.dev"
    exit 0
  fi
  sleep 5
done
echo "deployed, but /healthz did not answer yet; check: $FLY logs -a $APP" >&2
exit 1
