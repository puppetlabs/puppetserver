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

# Configure puppet to use a certificate autosign script (if it exists)
# AUTOSIGN=true|false|path_to_autosign.conf
if test -n "${AUTOSIGN}" ; then
  puppet config set autosign "$AUTOSIGN" --section master
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

exec /opt/puppetlabs/bin/puppetserver "$@"
