#!/bin/bash

chown -R puppet:puppet /etc/puppetlabs/puppet/
chown -R puppet:puppet /opt/puppetlabs/server/data/puppetserver/

# During build, pristine config files get copied to this directory. If
# they are not in the current container, use these templates as the
# default
TEMPLATES=/var/tmp/puppet

cd /etc/puppetlabs/puppet
for f in auth.conf hiera.yaml puppet.conf puppetdb.conf
do
    if ! test -f $f ; then
        cp -p $TEMPLATES/$f .
    fi
done
cd /

if test -n "${PUPPETDB_SERVER_URLS}" ; then
  sed -i "s@^server_urls.*@server_urls = ${PUPPETDB_SERVER_URLS}@" /etc/puppetlabs/puppet/puppetdb.conf
fi

if test -n "${PUPPETSERVER_HOSTNAME}"; then
  /opt/puppetlabs/bin/puppet config set certname "$PUPPETSERVER_HOSTNAME"
  /opt/puppetlabs/bin/puppet config set server "$PUPPETSERVER_HOSTNAME"
fi

# Allow setting the dns_alt_names for the server's certificate. This
# setting will only have an effect when the container is started without
# an existing certificate on the /etc/puppetlabs/puppet volume
if test -n "${DNS_ALT_NAMES}"; then
    fqdn=$(facter fqdn)
    if test ! -f "/etc/puppetlabs/puppet/ssl/certs/$fqdn.pem" ; then
        puppet config set dns_alt_names "${DNS_ALT_NAMES}" --section master
    else
        actual=$(puppet config print dns_alt_names --section master)
        if test "${DNS_ALT_NAMES}" != "${actual}" ; then
            echo "Warning: DNS_ALT_NAMES has been changed from the value in puppet.conf"
            echo "         Remove/revoke the old certificate for this to become effective"
        fi
    fi
fi

# Disable local CA if using external CA or multi-master configuration
# CA service enabled by default.
if test "${CA_DISABLE}" = "true"; then
  sed -i -e 's@^\(puppetlabs.services.ca.certificate-authority-service/certificate-authority-service\)@# \1@' -e 's@.*\(puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service\)@\1@' /etc/puppetlabs/puppetserver/services.d/ca.cfg

# MUST provide CA_SERVER hostname
# Use fqdn hostname to specify CA servers.
  if test -n "${CA_SERVER}" ; then
    # Generate SSL certificates if missing.
    # May need manual signing on the CA server
    # But first wait for CA server to be ready
    fqdn=$(facter fqdn)
    if [ ! -f "/etc/puppetlabs/puppet/ssl/certs/$fqdn.pem" ]; then
      while ! nc -z "$CA_SERVER" 8140; do
        sleep 1
      done
      
      puppet agent --noop --server=$CA_SERVER
    fi
    # Workaround fix on non-ca Puppetmasters. Default ssl-crl-path=/etc/puppetlabs/puppet/ssl/ca/ca_crl.pem
    if [ ! -d "/etc/puppetlabs/puppet/ssl/ca" ]; then
      mkdir /etc/puppetlabs/puppet/ssl/ca
      ln -s /etc/puppetlabs/puppet/ssl/crl.pem /etc/puppetlabs/puppet/ssl/ca/ca_crl.pem
    fi
  else
    echo "Warning: NO CA_SERVER is provided"
    echo "         Please provide CA_SERVER hostname"
    echo "         Otherwise any Certificate Signing Request from this container will fail"
  fi

else
  # Configure CA server. This is the default behavior
  # Configure puppet to use a certificate autosign script (if it exists)
  # AUTOSIGN=true|false|path_to_autosign.conf
  if test -n "${AUTOSIGN}" ; then
    puppet config set autosign "$AUTOSIGN" --section master
  fi
fi

exec /opt/puppetlabs/bin/puppetserver "$@"
