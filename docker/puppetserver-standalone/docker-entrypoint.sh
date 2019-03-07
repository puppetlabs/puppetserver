#!/bin/bash

set -e

for f in /docker-entrypoint.d/*.sh; do
    echo "Running $f"
    chmod +x "$f"
    sync
    "$f"
done

exec /opt/puppetlabs/bin/puppetserver "$@"
