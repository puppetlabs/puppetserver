#!/bin/bash
set -x

export GEM_SOURCE=https://artifactory.delivery.puppetlabs.net/artifactory/api/gems/rubygems/

export GENCONFIG_LAYOUT="${GENCONFIG_LAYOUT:-redhat6-64ma-redhat6-64a-redhat6-64u}"
export BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-acceptance/suites/tests/00_smoke}"
export BEAKER_PRESUITE="${BEAKER_PRESUITE:-acceptance/suites/pre_suite/foss}"
export BEAKER_POSTSUITE="acceptance/suites/post_suite/"
export BEAKER_OPTIONS="acceptance/config/beaker/options.rb"
export BEAKER_CONFIG="acceptance/scripts/hosts.cfg"
export BEAKER_KEYFILE="~/.ssh/id_rsa-acceptance"
export BEAKER_HELPER="acceptance/lib/helper.rb"
# export PACKAGE_BUILD_VERSION="2.1.2.SNAPSHOT.2015.08.20T0208"
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
  --preserve-hosts \
  --debug \
  --timeout 360

cp log/latest/hosts_preserved.yml .
