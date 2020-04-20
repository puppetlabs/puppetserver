#!/bin/bash

# Allow setting dns_alt_names for the compilers certificate. This
# setting will only have an effect when the container is started without
# an existing certificate on the /etc/puppetlabs/puppet volume
if [ -n "${DNS_ALT_NAMES}" ] && [ "${CA_ENABLED}" != "true" ]; then
    certname=$(puppet config print certname)
    if test ! -f "${SSLDIR}/certs/$certname.pem" ; then
        puppet config set dns_alt_names "${DNS_ALT_NAMES}" --section agent
    else
        actual=$(puppet config print dns_alt_names --section "${config_section}")
        if test "${DNS_ALT_NAMES}" != "${actual}" ; then
            echo "Warning: DNS_ALT_NAMES has been changed from the value in puppet.conf"
            echo "         Remove/revoke the old certificate for this to become effective"
        fi
    fi
fi
