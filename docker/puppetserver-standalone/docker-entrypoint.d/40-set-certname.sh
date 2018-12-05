#!/bin/bash

if test -n "${PUPPETSERVER_HOSTNAME}"; then
  /opt/puppetlabs/bin/puppet config set certname "$PUPPETSERVER_HOSTNAME"
  /opt/puppetlabs/bin/puppet config set server "$PUPPETSERVER_HOSTNAME"
fi
