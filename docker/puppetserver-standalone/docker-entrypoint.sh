#!/bin/bash

chown -R puppet:puppet /etc/puppetlabs/puppet/ssl
chown -R puppet:puppet /opt/puppetlabs/server/data/puppetserver/

if test -n "${PUPPETDB_SERVER_URLS}" ; then
  sed -i "s@^server_urls.*@server_urls = ${PUPPETDB_SERVER_URLS}@" /etc/puppetlabs/puppet/puppetdb.conf
fi

if test -n "${PUPPETSERVER_HOSTNAME}"; then
  /opt/puppetlabs/bin/puppet config set certname "$PUPPETSERVER_HOSTNAME"
  /opt/puppetlabs/bin/puppet config set server "$PUPPETSERVER_HOSTNAME"
fi

# Configure puppet to use a certificate autosign script (if it exists)
# AUTOSIGN=true|false|path_to_autosign.conf
if test -n "${AUTOSIGN}" ; then
  puppet config set autosign "$AUTOSIGN" --section master
fi

exec /opt/puppetlabs/bin/puppetserver "$@"
