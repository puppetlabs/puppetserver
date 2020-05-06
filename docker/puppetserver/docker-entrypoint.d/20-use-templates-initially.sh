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

if [ -d /var/tmp/puppetserver/vendored-jruby-gems ]; then
  echo "Upgrading /opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems"
  # clean up existing vendored gems
  rm -rf /opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems
  cp -R /var/tmp/puppetserver/vendored-jruby-gems /opt/puppetlabs/server/data/puppetserver/
  # remove the tmp dir so we only run this on first runs of new containers
  rm -rf /var/tmp/puppetserver/vendored-jruby-gems
fi
