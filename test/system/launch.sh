#!/bin/bash

###############################################################################
# This beaker launch script serves the following purposes:
# - enable the easy launching of beaker tests for review, debugging, and 
#     for non Jenkins/CI testing.
# - make test script debugging cheaper by enabling the graceful reuse of 
#      hosts that have already installed PE/puppet.
# - emulate the manner in which test scripts are launched in Jenkins where 
#      appropriate and possible.
# - to start to reduce reliance on the previous custom launcher scripts by 
#      providing more generic methods

#if [ -z "$BEAKER_TESTSUITE" ];
#  then echo "$0 could not find \$BEAKER_TESTSUITE in the enviornment."
#  echo "Please set \$BEAKER_TESTSUITE and try again."
#  exit -1;
#fi

if [ -z "$pe_dist_dir" ];             then export pe_dist_dir="http://neptune.puppetlabs.lan/2015.2/ci-ready"; fi

BEAKER_KEYFILE="${BEAKER_KEYFILE:-$HOME/.ssh/id_rsa-acceptance}"
# BEAKER_LOADPATH="${BEAKER_LOADPATH:-lib/}"
BEAKER_PRESUITE="${BEAKER_PRESUITE:-pre-suite/}"
# BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-$(echo suites/puppet/tests/031_puppet_resource_exec/ | tr ' ' ',')}"
# BEAKER_POSTSUITE="${BEAKER_POSTSUITE:-}"
BEAKER_TYPE="${BEAKER_TYPE:-aio}"
PLATFORM="${PLATFORM:-redhat7}"

configYaml=config.yaml
BEAKER="bundle exec beaker --debug --root-keys --repo-proxy"
BEAKER="$BEAKER --type $BEAKER_TYPE"
BEAKER="$BEAKER --keyfile $BEAKER_KEYFILE"
# BEAKER="$BEAKER --tests $BEAKER_TESTSUITE"
if [ ! -z "$BEAKER_HELPER" ];           then BEAKER="$BEAKER --helper $BEAKER_HELPER"; fi
if [ ! -z "$BEAKER_OPTIONSFILE" ];      then BEAKER="$BEAKER --options-file $BEAKER_OPTIONSFILE"; fi
if [ ! -z "$BEAKER_POSTSUITE" ];        then BEAKER="$BEAKER --post-suite $BEAKER_POSTSUITE"; fi
if [ ! -z "$BEAKER_LOADPATH" ];         then BEAKER="$BEAKER --load-path $BEAKER_LOADPATH"; fi

# while [ ! -d suites -a ! -d manifests ] ; do cd .. ; done

#################
mkBeakerHostConfig () {
  ips=()
  for h in $(cat beakerhosts)
  do
    ips+=( $h )
  done
  
  cp $configYaml existing
  perl -pi -e 's/hypervisor: vmpooler\n/hypervisor: vmpooler\n    ip:\n/mg' existing
  for i in ${ips[@]}
    do
    echo inserting $i
    perl -pi -e "\$a=1 if(!\$a && s/ip:\n/ip: $i\n/);" existing
  done
}

case "$1" in
--p* )
  echo "$0 Preserving hosts and saving hostnames for reuse"

  if [ -z "$genconfig_string" ]; then 
    genconfig_string="$PLATFORM-64mdcla-64fa,compile_master.-64fa,compile_master.-64a-64a"
  fi

  #external ca is a bit of a special snowflake, we have to have genconfig_string set to 
  #$someplatform-$somelayout-$etc_platform-$etc_layout-centos6-64u --disable-default-role
  genconfig2 $genconfig_string > $configYaml
  
  BEAKER="$BEAKER --hosts $configYaml"
  BEAKER="$BEAKER --pre-suite $BEAKER_PRESUITE"
  BEAKER="$BEAKER --preserve-hosts"
  $BEAKER | tee beakerlog.log
  
  echo "$0 checking contents of beakerlog.log"
  for h in $(grep "Using available host" beakerlog.log | grep -oE "[0-9a-z]{15}.delivery.puppetlabs.net" )
    do
    echo "\t$0 found host $h.  Storing in beakerhosts"
    echo $h >> beakerhosts
  done
 
  echo "$0 building $configYaml"
  mkBeakerHostConfig
  ;;

 
--r*|--u*)
  echo "$0 is resuing hosts from $configYaml."
  
  BEAKER="$BEAKER --hosts existing" 
  BEAKER="$BEAKER --no-provision --preserve-hosts"
  echo "Launching $BEAKER from $(pwd) ..."
  $BEAKER | tee beakerlog.log
  ;;

* )
  echo "$0 usage:"
  echo "$0 --p[reserve]    Runs beaker with --preservehosts.  Stores hosts in beakerhosts"
  echo "$0 --r[euse]       Loads hosts from beakerhosts and feeds them into a config.yaml for beaker."
  echo "Use the --r option when puppet is sucessfully installed."
  ;;

esac
