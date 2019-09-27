#!/bin/sh

set -x
set -e

curl --fail \
    --resolve "${HOSTNAME}:${PUPPET_MASTERPORT}:127.0.0.1" \
    --cert    "${SSLDIR}/certs/${CERTNAME}.pem" \
    --key     "${SSLDIR}/private_keys/${CERTNAME}.pem" \
    --cacert  "${SSLDIR}/certs/ca.pem" \
    "https://${HOSTNAME}:${PUPPET_MASTERPORT}/status/v1/simple" \
    |  grep -q '^running$' \
    || exit 1
