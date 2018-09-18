#!/usr/bin/env bash

set -x
set -e

curl --fail -H 'Accept: pson' \
--resolve 'puppet:8140:127.0.0.1' \
--cert   $(puppet config print hostcert) \
--key    $(puppet config print hostprivkey) \
--cacert $(puppet config print localcacert) \
https://puppet:8140/${PUPPET_HEALTHCHECK_ENVIRONMENT}/status/test \
|  grep -q '"is_alive":true' \
|| exit 1
