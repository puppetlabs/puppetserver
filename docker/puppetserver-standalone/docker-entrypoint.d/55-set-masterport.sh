#!/bin/bash

hocon() {
  /opt/puppetlabs/puppet/lib/ruby/vendor_gems/bin/hocon "$@"
}

if test -n "$PUPPET_MASTERPORT"; then
  cd /etc/puppetlabs/puppetserver/conf.d/
  hocon -f webserver.conf set webserver.ssl-port $PUPPET_MASTERPORT
  cd /
fi
