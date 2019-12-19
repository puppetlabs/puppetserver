#!/bin/bash

set -e

chmod +x /docker-entrypoint.d/*.sh
# sync prevents aufs from sometimes returning EBUSY if you exec right after a chmod
sync
for f in /docker-entrypoint.d/*.sh; do
    echo "Running $f"
    "$f"
done

if [ -d /docker-custom-entrypoint.d/ ]; then
    find /docker-custom-entrypoint.d/ -type f -name "*.sh" \
        -exec chmod +x {} \;
    sync
    find /docker-custom-entrypoint.d/ -type f -name "*.sh" \
        -exec echo Running {} \; -exec {} \;
fi

exec /opt/puppetlabs/bin/puppetserver "$@"
