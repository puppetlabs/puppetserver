#!/bin/bash

# Allow setting the dns_alt_names for the server's certificate. This
# setting will only have an effect when the container is started without
# an existing certificate on the /etc/puppetlabs/puppet volume
if test -n "${DNS_ALT_NAMES}"; then
    config_section="master"
    if [[ "${CA_ENABLED}" != "true" ]]; then
      # we are just an ordinary compiler using puppet agent to generate cert
      config_section="agent"
    fi

    certname=$(puppet config print certname)
    if test ! -f "/etc/puppetlabs/puppet/ssl/certs/$certname.pem" ; then
        puppet config set dns_alt_names "${DNS_ALT_NAMES}" --section "${config_section}"
    else
        actual=$(puppet config print dns_alt_names --section "${config_section}")
        if test "${DNS_ALT_NAMES}" != "${actual}" ; then
            echo "Warning: DNS_ALT_NAMES has been changed from the value in puppet.conf"
            echo "         Remove/revoke the old certificate for this to become effective"
        fi
    fi
fi
