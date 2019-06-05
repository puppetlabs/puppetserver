#!/usr/bin/env bash

set -x
set -e

certname=$(ls /etc/puppetlabs/puppet/ssl/certs | grep --invert-match ca.pem) && \
hostname=$(basename $certname .pem) && \
hostprivkey=/etc/puppetlabs/puppet/ssl/private_keys/$certname && \
hostcert=/etc/puppetlabs/puppet/ssl/certs/$certname && \
localcacert=/etc/puppetlabs/puppet/ssl/certs/ca.pem && \
curl --fail \
--resolve "${hostname}:${PUPPET_MASTERPORT}:127.0.0.1" \
--cert   $hostcert \
--key    $hostprivkey \
--cacert $localcacert \
"https://${hostname}:${PUPPET_MASTERPORT}/${PUPPET_HEALTHCHECK_ENVIRONMENT}/status/test" \
|  grep -q '"is_alive":true' \
|| exit 1
