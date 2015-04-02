#!/bin/bash
#########
# This script provides: 
# -- an easy way to run the puppet-server acceptance tests on vmpooler host(s)
# -- the ability to keep vmpooler(s) around and rerun arbitrary acceptance 
#    tests on them to aid with acceptance test development.
#########

# Point the present working directory to the top of the repository 
SCRIPT_BASE_PATH=$(eval basename $(pwd))
if [ $SCRIPT_BASE_PATH = "beaker" ]; then
  cd ../..
fi

#TODO: read PACKAGE_BUILD_VERSION from an external source and the enviornment
#    so that we don't have a hard coded value here
#    if you are manually updating this value you should be checking on
#    http://builds.puppetlabs.lan/puppetserver
export PACKAGE_BUILD_VERSION=1.0.8

#TODO: read configYaml and tests in from the enviornment and perhaps command line
#  and only assign defalut values that are very general 
configYaml=acceptance/config/beaker/jenkins/redhat-7-x86_64-mdca.cfg
tests=acceptance/suites/tests/555-puppetserver-service/reboot.rb
presuite=acceptance/suites/pre_suite/foss

BEAKEROPTS="--debug \
--keyfile ~/.ssh/id_rsa-acceptance \
--helper acceptance/lib/helper.rb \
--load-path ruby/puppet/acceptance/lib \
--options acceptance/config/beaker/options.rb \
--tests $tests \
--post-suite acceptance/suites/post_suite \
--type foss "

#########
# mkBeakerHostConfig copies and rewrites an existing beaker host config
# so that it contains the ipaddresses or hostnames of existing and provisioned 
# hosts.  
mkBeakerHostConfig () {
cp $configYaml existing
perl -pi -e 's/hypervisor: vcloud\n/hypervisor: vcloud\n    ip:\n/mg' existing
for i in ${ips[@]}
do
        echo inserting $i
        perl -pi -e "\$a=1 if(!\$a && s/ip:\n/ip: $i\n/);" existing
done
}
#########

if [ ! -e "ruby/puppet/bin/puppet" ] 
  then
  git submodule init
  git submodule sync
  git submodule update
fi

case "$1" in
  "" )
    beaker $BEAKEROPTS \
    --hosts $configYaml \
    --pre-suite $presuite \
  ;;

  --p* )
    echo "$0 Preserving hosts and saving hostnames."
    # TODO: if .beakerhosts exists, we should kill the hosts inside, wipe 
    #     existing, and continue.
    beaker $BEAKEROPTS \
    --hosts $configYaml \
    --pre-suite $presuite \
    --preserve-hosts | \
    tee .beakerlog.log

    echo "$0 checking contents of .beakerlog.log"
    # TODO: make this also work with ec2 hosts.
    for h in $(grep "Using available host" .beakerlog.log | grep -oE "[0-9a-z]{15}.delivery.puppetlabs.net" )
      do
      echo "\t$0 found host $h.  Storing in .beakerhosts"
      echo $h >> .beakerhosts
      done
  ;;

  --r*|--u* )
    echo "$0 Looking for hosts to reuse in .beakerhosts"
    # TODO: if .beakerhosts exists, has the required number of entries,
    #     and if each of the hosts are reachable, then we can proceed.
    ips=()
    for h in $(cat .beakerhosts)
      do
      echo $h
      ips+=( $h )
      done
    mkBeakerHostConfig ${ips[@]}
    beaker $BEAKEROPTS \
    --hosts existing \
    --no-provision \
    --preserve-hosts \
  ;;

  * )
    echo "$1 is not a valid parameter"
    echo "usage:"
    echo "$0 \[--preserve|--useExisting\]"
    echo "\t --p --preserve : Launches beaker with --preserve-hosts option set.  Stores resulting hosts for later use."
    echo "\t --r --u --useExisting: looks for hosts in .beakerhosts: launches beaker with options to use existing hosts."
  ;;

esac

