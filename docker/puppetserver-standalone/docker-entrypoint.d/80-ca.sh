#!/bin/bash

certname=$(puppet config print certname)

if [[ "$ENABLE_CA" = "true" ]]; then
  cd /etc/puppetlabs/puppet/ssl
  # we are the master runnning the CA
  if [[ ! -f ca/ca_key.pem ]]; then
    echo "initializing the CA"
    puppetserver ca setup --ca-name "Pupperware on $certname" \
      --certname "$certname" --subject-alt-names "$DNS_ALT_NAMES"
  fi
  cd /
else
  # we are just an ordinary compiler
  echo "turning off CA"
  cat > /etc/puppetlabs/puppetserver/services.d/ca.cfg <<EOF
puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
puppetlabs.trapperkeeper.services.watcher.filesystem-watch-service/filesystem-watch-service
EOF
fi
