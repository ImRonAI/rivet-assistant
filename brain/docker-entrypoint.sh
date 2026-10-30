#!/bin/sh
# Fly (and most hosts) mount volumes owned by root; hand /data to the unprivileged user, then drop to it.
set -e
if [ "$(id -u)" = "0" ]; then
  mkdir -p /data
  chown -R emrys:emrys /data
  exec setpriv --reuid=emrys --regid=emrys --init-groups "$@"
fi
exec "$@"
