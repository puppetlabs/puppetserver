#!/usr/bin/env bash

set -x
set -e

hostname=$(puppet config print certname) && \
masterport=$(puppet config print masterport) && \
curl --fail \
--resolve "${hostname}:${masterport}:127.0.0.1" \
--cert   $(puppet config print hostcert) \
--key    $(puppet config print hostprivkey) \
--cacert $(puppet config print localcacert) \
"https://${hostname}:${masterport}/${PUPPET_HEALTHCHECK_ENVIRONMENT}/status/test" \
|  grep -q '"is_alive":true' \
|| exit 1
