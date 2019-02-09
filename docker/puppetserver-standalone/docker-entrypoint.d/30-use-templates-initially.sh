#! /bin/bash

# During build, pristine config files get copied to this directory. If
# they are not in the current container, use these templates as the
# default
TEMPLATES=/var/tmp/puppet

cd /etc/puppetlabs/puppet
for f in auth.conf hiera.yaml puppet.conf puppetdb.conf
do
    test -f "$TEMPLATES/$f" && cp -np "$TEMPLATES/$f" .
done
cd /
