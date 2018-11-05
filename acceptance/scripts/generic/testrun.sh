#!/bin/bash
set -x

export GEM_SOURCE="https://artifactory.delivery.puppetlabs.net/artifactory/api/gems/rubygems/"
export GENCONFIG_LAYOUT="${GENCONFIG_LAYOUT:-redhat6-64ma-ubuntu1604-64a-windows2012r2-64a}"
export BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-acceptance/suites/tests}"
export BEAKER_PRESUITE="${BEAKER_PRESUITE:-acceptance/suites/pre_suite/foss}"
export BEAKER_POSTSUITE="${BEAKER_POSTSUITE:-acceptance/suites/post_suite}"
export BEAKER_OPTIONS="${BEAKER_OPTIONS:-acceptance/config/beaker/options.rb}"
export BEAKER_CONFIG="${BEAKER_CONFIG:-acceptance/scripts/hosts.cfg}"
export BEAKER_KEYFILE="${BEAKER_KEYFILE:-~/.ssh/id_rsa-acceptance}"
export BEAKER_HELPER="${BEAKER_HELPER:-acceptance/lib/helper.rb}"

bundle install --path vendor/bundle

#TODO: Someday, when our Rakefile is refactored, this section will go away
BEAKER="bundle exec beaker --debug"
BEAKER="$BEAKER --type aio"
BEAKER="$BEAKER --keyfile $BEAKER_KEYFILE"
BEAKER="$BEAKER --helper $BEAKER_HELPER"
BEAKER="$BEAKER --options-file $BEAKER_OPTIONS"
BEAKER="$BEAKER --post-suite $BEAKER_POSTSUITE"
BEAKER="$BEAKER --load-path acceptance/lib"

if [ -z "$PACKAGE_BUILD_VERSION" ];
  #TODO: curl builds.puppetlabs.lan/puppetserver.  Parse HTML, find most recent folder name.
  then echo "$0: PACKAGE_BUILD_VERSION not set.  Please export PACKAGE_BUILD_VERSION."
  exit -1;
fi

case $1 in
  -p | --p* )
  bundle exec beaker-hostgenerator $GENCONFIG_LAYOUT > $BEAKER_CONFIG

  BEAKER="$BEAKER --pre-suite $BEAKER_PRESUITE"
  BEAKER="$BEAKER --tests $BEAKER_TESTSUITE"
  BEAKER="$BEAKER --config $BEAKER_CONFIG"
  BEAKER="$BEAKER --preserve-hosts always"
  $BEAKER
  echo "Beaker exited with $?"
  cp log/latest/hosts_preserved.yml .
  ;;


-r | --r* )
  if [ ! -s ./hosts_preserved.yml ];
  then echo "$0: Can not find hosts_preserved.yml; can not run without presuite.\n \
    Either provide a hosts_preserved.yml or use this script with -p to create new hosts and run the pre-suite against them."
    exit -1;
  fi

  BEAKER="$BEAKER --config hosts_preserved.yml"
  BEAKER="$BEAKER --preserve-hosts always"
  BEAKER="$BEAKER --tests $BEAKER_TESTSUITE"
  $BEAKER
  ;;


* )
  bundle exec beaker-hostgenerator $GENCONFIG_LAYOUT > $BEAKER_CONFIG

  # run it with the old options.
  BEAKER="$BEAKER --pre-suite $BEAKER_PRESUITE"
  BEAKER="$BEAKER --config $BEAKER_CONFIG"
  BEAKER="$BEAKER --pre-suite $BEAKER_PRESUITE"
  BEAKER="$BEAKER --tests $BEAKER_TESTSUITE"
  BEAKER="$BEAKER --preserve-hosts onfail"
  BEAKER="$BEAKER --debug --timeout 360"
   $BEAKER
  ;;
esac

