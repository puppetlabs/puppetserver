#!/bin/bash

if test -n "${PUPPETSERVER_MAX_ACTIVE_INSTANCES}"; then
  sed -i "s/#max-active-instances/max-active-instances/" /etc/puppetlabs/puppetserver/conf.d/puppetserver.conf
fi
