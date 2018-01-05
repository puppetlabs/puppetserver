#!/bin/bash
set -x

export GEM_SOURCE=https://artifactory.delivery.puppetlabs.net/artifactory/api/gems/rubygems/

export GENCONFIG_LAYOUT="${GENCONFIG_LAYOUT:-redhat6-64ma-redhat6-64a-redhat6-64u}"
export BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-acceptance/suites/tests/020-external-ca/}"
export BEAKER_PRESUITE="${BEAKER_PRESUITE:-acceptance/suites/pre_suite/foss}"
export BEAKER_POSTSUITE="acceptance/suites/post_suite/"
export BEAKER_OPTIONS="acceptance/config/beaker/options.rb"
export BEAKER_CONFIG="hosts_preserved.yml"
export BEAKER_KEYFILE="~/.ssh/id_rsa-acceptance"
export BEAKER_HELPER="acceptance/lib/helper.rb"

bundle install --path vendor/bundle


bundle exec beaker \
  --no-provision \
  --hosts $BEAKER_CONFIG \
  --type aio \
  --helper $BEAKER_HELPER \
  --options-file $BEAKER_OPTIONS \
  --tests $BEAKER_TESTSUITE \
  --post-suite $BEAKER_POSTSUITE \
  --keyfile $BEAKER_KEYFILE \
  --load-path "acceptance/lib" \
  --preserve-hosts \
  --debug \
  --timeout 360


