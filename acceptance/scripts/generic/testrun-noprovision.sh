#!/bin/bash
set -x

# command line parameters
export BEAKER_CONFIG="${2:-acceptance/scripts/hosts.cfg}"
export BEAKER_TESTSUITE="${1:-acceptance/tests/}"

export GEM_SOURCE=http://rubygems.delivery.puppetlabs.net
export BEAKER_HELPER="acceptance/lib/helper.rb"
export BEAKER_KEYFILE="~/.ssh/id_rsa-acceptance"

bundle install --path vendor/bundle

bundle exec beaker \
  --config $BEAKER_CONFIG \
  --type aio \
  --helper $BEAKER_HELPER \
  --tests $BEAKER_TESTSUITE \
  --keyfile $BEAKER_KEYFILE \
  --load-path "acceptance/lib" \
  --preserve-hosts onfail \
  --no-provision \
  --no-validate \
  --no-configure \
  --debug \
  --timeout 360
