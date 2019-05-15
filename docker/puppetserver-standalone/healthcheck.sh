#!/usr/bin/env bash
#
# shellcheck disable=SC1091,SC2154


set -x
set -e

# since it takes ~1 second at 100% CPU to receive a single setting using `puppet
# config` we cache them
if [[ ! -f /tmp/puppet_env ]]; then
  puppet config print --render-as yaml certname hostcert hostprivkey localcacert | tail -n+2 | sed -re 's/^/puppet_/g' -e 's/: /=/g' > /tmp/puppet_env
fi

set -a
. /tmp/puppet_env
set +a

hostname="${puppet_certname}" && \
curl --fail \
--resolve "${hostname}:8140:127.0.0.1" \
--cert   "${puppet_hostcert}" \
--key    "${puppet_hostprivkey}" \
--cacert "${puppet_localcacert}" \
"https://${hostname}:8140/${PUPPET_HEALTHCHECK_ENVIRONMENT}/status/test" \
|  grep -q '"is_alive":true' \
|| exit 1
