#!/usr/bin/env bash
# One-time move of the workspace VM from the Rivet names to the Emrys names. Run it on the VM as root.
#
#   sudo bash migrate-to-emrys.sh <git-ref>            # dry run: prints every step, changes nothing
#   sudo bash migrate-to-emrys.sh <git-ref> --apply    # does it
#
# <git-ref> is the commit or branch of ImRonAI/rivet-assistant to build the brain from (the Emrys code reads only
# EMRYS_* settings and Emrys paths, so the brain must not restart on the new image before this has run).
#
# Steps, each skipped when already done, so a second run is safe:
#   1. build emrys-brain:<sha> from <git-ref> in src/ while the old stack keeps serving;
#   2. stop the stack in /opt/rivet (its volumes stay) and move the folder to /opt/emrys;
#   3. rename RIVET_* keys to EMRYS_* in brain.env and laptop.env;
#   4. move the brain's files: data/sessions/session/<id>/rivet -> emrys, data/sessions/memory/rivet -> emrys;
#   5. copy the compose volumes (Caddy's certificates, the laptop's home) from rivet_* to emrys_*, because Compose
#      names volumes after the folder; the rivet_* volumes are kept until the owner removes them;
#   6. point compose.yaml at the new image, start the stack as project "emrys" and wait for /healthz.
# Afterwards, call answering setup has to run once from the phone (it creates the Emrys assistant; removing the old
# Rivet assistant goes through the approval sheet).
set -euo pipefail

OLD=/opt/rivet
NEW=/opt/emrys
HEALTH=https://rivet.ron-ai.dev/healthz
VOLUMES=(caddy_data caddy_config laptop_home)

ref="${1:?usage: migrate-to-emrys.sh <git-ref> [--apply]}"
apply=0
if [ "${2:-}" = "--apply" ]; then apply=1; fi

run() {
  if [ "$apply" = 1 ]; then
    echo "+ $*"
    "$@"
  else
    echo "would run: $*"
  fi
}

if [ "$(id -u)" != 0 ]; then
  echo "run as root (sudo)" >&2
  exit 1
fi

if [ -d "$OLD" ] && [ -e "$NEW" ]; then
  echo "both $OLD and $NEW exist; resolve that by hand first" >&2
  exit 1
elif [ -d "$OLD" ]; then
  root="$OLD"
elif [ -d "$NEW" ]; then
  echo "$OLD is already moved to $NEW"
  root="$NEW"
else
  echo "neither $OLD nor $NEW exists" >&2
  exit 1
fi

# 1: build the brain from <git-ref>. Git runs as the checkout's owner, which keeps .git's ownership and is the only
# user git trusts there.
owner="$(stat -c %U "$root/src")"
run sudo -u "$owner" git -C "$root/src" fetch origin "$ref"
run sudo -u "$owner" git -C "$root/src" checkout --detach FETCH_HEAD
if [ "$apply" = 1 ]; then
  sha="$(sudo -u "$owner" git -C "$root/src" rev-parse --short HEAD)"
else
  sha="<sha of $ref>"
fi
run docker build -t "emrys-brain:$sha" "$root/src/brain"

# 2: stop the old stack and move its folder.
if [ "$root" = "$OLD" ]; then
  run docker compose --project-directory "$OLD" -p rivet down
  run mv "$OLD" "$NEW"
  # In a dry run nothing moved, so the remaining steps look at the old folder.
  if [ "$apply" = 1 ]; then root="$NEW"; fi
fi

# 3: settings names.
for env in brain.env laptop.env; do
  if [ -f "$root/$env" ] && grep -q '^RIVET_' "$root/$env"; then
    run sed -i 's/^RIVET_/EMRYS_/' "$root/$env"
  fi
done

# 4: the brain's files.
sessions="$root/data/sessions"
for dir in "$sessions"/session/*/rivet "$sessions"/memory/rivet; do
  [ -d "$dir" ] || continue
  target="$(dirname "$dir")/emrys"
  if [ -e "$target" ]; then
    echo "both $dir and $target exist; resolve that by hand first" >&2
    exit 1
  fi
  run mv "$dir" "$target"
done

# 5: the compose volumes.
for name in "${VOLUMES[@]}"; do
  if ! docker volume inspect "rivet_$name" >/dev/null 2>&1; then
    echo "no volume rivet_$name; nothing to copy"
    continue
  fi
  if docker volume inspect "emrys_$name" >/dev/null 2>&1; then
    echo "emrys_$name already exists; not copying"
    continue
  fi
  run docker volume create "emrys_$name"
  run docker run --rm -v "rivet_$name:/from:ro" -v "emrys_$name:/to" --entrypoint sh caddy:2.11.7 -c 'cp -a /from/. /to/'
done

# 6: start on the new image and check.
run sed -i -E "s#^(    image: )(rivet|emrys)-brain:.*#\\1emrys-brain:$sha#" "$root/compose.yaml"
run docker compose --project-directory "$root" -p emrys up -d
if [ "$apply" = 1 ]; then
  for _ in $(seq 1 30); do
    if curl -fsS "$HEALTH" >/dev/null; then
      echo "live: $HEALTH answers on emrys-brain:$sha"
      exit 0
    fi
    sleep 5
  done
  echo "started, but $HEALTH did not answer yet; check: docker compose --project-directory $root -p emrys logs brain" >&2
  exit 1
fi
echo "dry run only; rerun with --apply to make these changes"
