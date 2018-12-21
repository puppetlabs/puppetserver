#!/bin/bash

set -e

for f in /docker-entrypoint.d/*.sh; do
  echo "Running $f"
  chmod +x "$f"
  "$f"
done

if [[ -n "$(ls -A /docker-entrypoint-additional.d)" ]]; then
  for f in /docker-entrypoint-additional.d/*; do
    case "$f" in
      *.sh)
        echo "Running $f"
        chmod +x "$f"
        "$f"
        ;;
      *)
        echo "ignoring $f, it's missing a .sh extension"
        ;;
    esac
  done
fi

exec /opt/puppetlabs/bin/puppetserver "$@"
