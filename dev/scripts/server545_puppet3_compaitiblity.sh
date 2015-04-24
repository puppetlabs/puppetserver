#! /bin/bash

# This script is expected to be run with a PWD of the root of the puppet-server
# project.


# Puppet Server package version (from http://builds.puppetlabs.lan/puppetserver/)
export PACKAGE_BUILD_VERSION='2.1.0.SNAPSHOT.2015.04.17T0057'
# Legacy means pre-aio (4.0) in this case
export PUPPET_LEGACY_VERSION='3.7.5'

  # --post-suite acceptance/suites/post_suite_backwards_compatibility \
bundle exec beaker \
  --debug \
  --root-keys \
  --no-color \
  --repo-proxy \
  --preserve-hosts never \
  --type aio \
  --config acceptance/nodes/redhat7-64m-64a.yaml \
  --pre-suite acceptance/suites/pre_suite/backwards_compatibility \
  --tests acceptance/suites/backwards_compatibility/010_hello_world \
  --keyfile ~/.ssh/id_rsa-acceptance \
  --helper acceptance/lib/helper.rb \
  --options-file acceptance/config/beaker/options.rb \
  --load-path acceptance/lib
