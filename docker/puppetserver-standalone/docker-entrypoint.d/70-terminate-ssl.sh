#!/bin/bash

if [[ "$TERMINATE_SSL" = "true" ]]; then
  # Change puppetserver settings to allow HTTP traffic since we're terminating SSL.
  # See https://puppet.com/docs/puppetserver/6.0/external_ssl_termination.html
  hocon() {
    /opt/puppetlabs/puppet/lib/ruby/vendor_gems/bin/hocon "$@"
  }

  cd /etc/puppetlabs/puppetserver/conf.d
  # Switch webserver to plain http
  hocon -f webserver.conf unset webserver.ssl-host
  hocon -f webserver.conf unset webserver.ssl-port
  hocon -f webserver.conf set webserver.host 0.0.0.0
  hocon -f webserver.conf set webserver.port 8140
  # Turn off legacy auth
  hocon -f puppetserver.conf set jruby-puppet.use-legacy-auth-conf false
  # Use HTTP headers for client auth
  hocon -f auth.conf set authorization.allow-header-cert-info true
fi
