#!/bin/bash


master_running() {
  status=$(curl --silent --fail --insecure "https://${CA_HOSTNAME}:8140/status/v1/simple")
  test "$status" = "running"
}

hocon() {
  /opt/puppetlabs/puppet/lib/ruby/vendor_gems/bin/hocon "$@"
}

CA_HOSTNAME="${CA_HOSTNAME:-puppet}"

if [[ "$ENABLE_CA" != "true" ]]; then
  # we are just an ordinary compiler
  echo "turning off CA"
  cat > /etc/puppetlabs/puppetserver/services.d/ca.cfg <<EOF
puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
puppetlabs.trapperkeeper.services.watcher.filesystem-watch-service/filesystem-watch-service
EOF

  ssl_cert=$(puppet master --configprint hostcert)
  ssl_key=$(puppet master --configprint hostprivkey)
  ssl_ca_cert=$(puppet master --configprint localcacert)
  certname=$(puppet config print certname)


  cd /etc/puppetlabs/puppetserver/conf.d/
  hocon -f webserver.conf set webserver.ssl-cert $ssl_cert
  hocon -f webserver.conf set webserver.ssl-key $ssl_key
  hocon -f webserver.conf set webserver.ssl-ca-cert $ssl_ca_cert
  hocon -f webserver.conf set webserver.ssl-crl-path /etc/puppetlabs/puppet/ssl/crl.pem
  cd /

  # bootstrap certs for the puppetserver
  if [[ ! -f "/etc/puppetlabs/puppet/ssl/certs/${certname}.pem" ]]; then
    while ! master_running; do
      sleep 1
    done

    puppet agent --noop --server="${CA_HOSTNAME}"
  fi
fi
