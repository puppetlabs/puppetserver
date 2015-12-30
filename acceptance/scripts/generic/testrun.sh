#!/bin/bash
set -x

export GEM_SOURCE=http://rubygems.delivery.puppetlabs.net

export GENCONFIG_LAYOUT="${GENCONFIG_LAYOUT:-redhat6-64ma-debian6-64a-windows2008r2-64a}"
export BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-acceptance/suites/tests/00_smoke}"
export BEAKER_PRESUITE="${BEAKER_PRESUITE:-acceptance/suites/pre_suite/foss}"
export BEAKER_POSTSUITE="acceptance/suites/post_suite/"
export BEAKER_OPTIONS="acceptance/config/beaker/options.rb"
export BEAKER_CONFIG="acceptance/scripts/hosts.cfg"
export BEAKER_KEYFILE="~/.ssh/id_rsa-acceptance"
export BEAKER_HELPER="acceptance/lib/helper.rb"

bundle install --path vendor/bundle

bundle exec beaker-hostgenerator $GENCONFIG_LAYOUT > $BEAKER_CONFIG

bundle exec beaker \
  --config $BEAKER_CONFIG \
  --type aio \
  --helper $BEAKER_HELPER \
  --options-file $BEAKER_OPTIONS \
  --tests $BEAKER_TESTSUITE \
  --post-suite $BEAKER_POSTSUITE \
  --pre-suite $BEAKER_PRESUITE \
  --keyfile $BEAKER_KEYFILE \
  --load-path "acceptance/lib" \
  --preserve-hosts onfail \
  --debug \
  --timeout 360
