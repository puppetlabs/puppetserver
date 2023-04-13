#!/bin/bash

do_init()
{
  if [ -z "$PACKAGE_BUILD_VERSION" ];
    #TODO: Read last passing SHA file on builds.pl.d.net
    then echo "$0: PACKAGE_BUILD_VERSION not set.  Please export PACKAGE_BUILD_VERSION."
    exit -1;
  fi

  bundle exec beaker-hostgenerator --hypervisor abs $GENCONFIG_LAYOUT > $BEAKER_CONFIG

  BEAKER_INIT="bundle exec beaker init --debug"
  BEAKER_INIT="$BEAKER_INIT --type aio"
  BEAKER_INIT="$BEAKER_INIT --keyfile $BEAKER_KEYFILE"
  BEAKER_INIT="$BEAKER_INIT --helper $BEAKER_HELPER"
  BEAKER_INIT="$BEAKER_INIT --options-file $BEAKER_OPTIONS"
  BEAKER_INIT="$BEAKER_INIT --post-suite $BEAKER_POSTSUITE"
  BEAKER_INIT="$BEAKER_INIT --load-path $BEAKER_LOADPATH"
  BEAKER_INIT="$BEAKER_INIT --hosts $BEAKER_CONFIG"
  BEAKER_INIT="$BEAKER_INIT --pre-suite $BEAKER_PRESUITE"
  BEAKER_INIT="$BEAKER_INIT --tests $BEAKER_TESTSUITE"
  BEAKER_INIT="$BEAKER_INIT --debug --timeout 360"
  $BEAKER_INIT

  if [ $? != 0 ] ; then
    echo "Initialization failed!"
    exit -1;
  fi

  bundle exec beaker provision

  if [ $? != 0 ] ; then
    echo "Provision failed!"
    exit -1;
  fi
}

set -x

export GEM_SOURCE="https://artifactory.delivery.puppetlabs.net/artifactory/api/gems/rubygems/"
export GENCONFIG_LAYOUT="${GENCONFIG_LAYOUT:-redhat8-64ma-ubuntu2004-64a}"
export BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-acceptance/suites/tests}"
export BEAKER_PRESUITE="${BEAKER_PRESUITE:-acceptance/suites/pre_suite/foss}"
export BEAKER_POSTSUITE="${BEAKER_POSTSUITE:-acceptance/suites/post_suite}"
export BEAKER_OPTIONS="${BEAKER_OPTIONS:-acceptance/config/beaker/options.rb}"
export BEAKER_CONFIG="${BEAKER_CONFIG:-acceptance/scripts/hosts.cfg}"
export BEAKER_KEYFILE="${BEAKER_KEYFILE:-~/.ssh/id_rsa-acceptance}"
export BEAKER_HELPER="${BEAKER_HELPER:-acceptance/lib/helper.rb}"
export BEAKER_LOADPATH="${BEAKER_LOADPATH:-acceptance/lib}"

bundle install --path vendor/bundle

case $1 in
  -p | --p* )
    do_init

    bundle exec beaker exec

    echo "Preserving hosts, run `bundle exec beaker exec <tests>` to use the same hosts again."
    ;;

  -r | --r* )
    if [ ! -s ./.beaker/subcommand_options.yaml ];
    then echo "$0: Can not find subcommand_options.yaml; cannot run without init.\n \
      Use this script with -p to create new hosts and initialize them."
      exit -1;
    fi

    bundle exec beaker exec $BEAKER_TESTSUITE
    ;;

  * ) # Preserve hosts on failure
    do_init

    bundle exec beaker exec

    if [ $? = 0 ] ; then
      echo "Run completed successfully, destroying hosts."
      bundle exec beaker destroy
      rm .beaker/subcommand_options.yaml
    else
      echo "Run failed, preserving hosts. Run against these hosts again with `bundle exec beaker exec <tests>`."
      echo "Alternatively, run this script again with `-r`."
    fi
    ;;
esac

