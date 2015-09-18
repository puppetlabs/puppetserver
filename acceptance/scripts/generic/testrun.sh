#!/bin/bash
set -x

# get to the correct directory
while [ ! -d acceptance ] ; do cd ..; done

export GEM_SOURCE=http://rubygems.delivery.puppetlabs.net

#The reason for these exports is that someday we want to pick them up in a rake file
export GENCONFIG_LAYOUT="${GENCONFIG_LAYOUT:-redhat6-64ma-ubuntu1404-64a-windows2008r2-64a}"
export BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-acceptance/suites/tests/00_smoke}"
export BEAKER_PRESUITE="${BEAKER_PRESUITE:-acceptance/suites/pre_suite/foss}"
export BEAKER_POSTSUITE="acceptance/suites/post_suite/"
export BEAKER_OPTIONS="acceptance/config/beaker/options.rb"
export BEAKER_CONFIG="acceptance/scripts/hosts.cfg"
export BEAKER_KEYFILE="~/.ssh/id_rsa-acceptance"
export BEAKER_HELPER="acceptance/lib/helper.rb"

bundle install --path vendor/bundle

#TODO: Someday, when our Rakefile is refactored, this section will go away
BEAKER="bundle exec beaker --debug"
BEAKER="$BEAKER --type aio"
BEAKER="$BEAKER --keyfile $BEAKER_KEYFILE"
BEAKER="$BEAKER --tests $BEAKER_TESTSUITE"
BEAKER="$BEAKER --helper $BEAKER_HELPER"
BEAKER="$BEAKER --options-file $BEAKER_OPTIONS"
BEAKER="$BEAKER --post-suite $BEAKER_POSTSUITE"
BEAKER="$BEAKER --load-path acceptance/lib"


case $1 in
  -p | --p* )
  bundle exec genconfig2 $GENCONFIG_LAYOUT > $BEAKER_CONFIG
    
  if [ -z "$BUILD_PACKAGE_VERSION" ]; 
    #TODO: curl builds.puppetlabs.lan/puppetserver.  Parse HTML, find most recent folder name.
    then echo "$0: BUILD_PACKAGE_VERSION not set.  Please export BUILD_PACAKGE_VERSION."
    exit -1;
  fi

  if [ -z BEAKER_PRESUITE ]; then echo "$0: BEAKER_PRESUITE not set."
    exit -1;
  fi
  
  BEAKER="$BEAKER --pre-suite $BEAKER_PRESUITE"
  BEAKER="$BEAKER --config $BEAKER_CONFIG"
  BEAKER="$BEAKER --preserve-hosts"
  $BEAKER
  echo "Beaker exited with $?"
  cp log/latest/hosts_preserved.yml .
  ;;


-r | --r* )
  if [ ! -s ./hosts_preserved.yml ];
  then echo "$0: Can not find hosts_preserved.yml; can not run without presuite./n \
    Either put a hosts_preserved.yml or use this script with -p to create new hosts and run the pre-suite against them"
    exit -1;
  fi

  if [ -z $BEAKER_TESTSUITE ];
    then echo "$0 BEAKER_TESTSUITE not set.  Export BEAKER_TESTSUITE."
    exit -1;
  fi
  
  BEAKER="$BEAKER --config hosts_preserved.yml"
  BEAKER="$BEAKER --preserve-hosts"
  $BEAKER
  ;;


* )
  # run it the old way  
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
  ;;
esac

