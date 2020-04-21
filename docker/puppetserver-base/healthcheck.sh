#!/usr/bin/env bash

set -x
set -e

certname=$(ls "${SSLDIR}/certs" | grep --invert-match ca.pem) && \
hostname=$(basename $certname .pem) && \
hostprivkey="${SSLDIR}/private_keys/$certname" && \
hostcert="${SSLDIR}/certs/$certname" && \
localcacert="${SSLDIR}/certs/ca.pem" && \
curl --fail \
--resolve "${hostname}:${PUPPET_MASTERPORT}:127.0.0.1" \
--cert   $hostcert \
--key    $hostprivkey \
--cacert $localcacert \
"https://${hostname}:${PUPPET_MASTERPORT}/status/v1/simple" \
|  grep -q '^running$' \
|| exit 1
