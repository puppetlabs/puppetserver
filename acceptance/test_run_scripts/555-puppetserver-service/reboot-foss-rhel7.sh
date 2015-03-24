#!/bin/bash
SCRIPT_PATH=$(pwd)
BASENAME_CMD="basename ${SCRIPT_PATH}"
SCRIPT_BASE_PATH=`eval ${BASENAME_CMD}`
if [ $SCRIPT_BASE_PATH = "555-puppetserver-service" ]; then
  cd ../../..
fi

export PACKAGE_BUILD_VERSION=1.0.4.SNAPSHOT.2015.03.24T0146

configYaml=acceptance/config/beaker/jenkins/redhat-7-x86_64-mdca.cfg
helper=acceptance/lib/helper.rb
options=acceptance/config/beaker/options.rb
tests=acceptance/suites/tests/555-puppetserver-service/reboot.rb
presuite=acceptance/suites/pre_suite/foss
postsuite=acceptance/suites/post_suite
BEAKER_TYPE=foss

BEAKEROPTS="--debug \
--keyfile ~/.ssh/id_rsa-acceptance \
--helper $helper \
--load-path ruby/puppet/acceptance/lib \
--options $options \
--tests $tests \
--post-suite $postsuite \
--type $BEAKER_TYPE "


#########
mkBeakerHostConfig () {
cp $configYaml existing

perl -pi -e 's/hypervisor: vcloud\n/hypervisor: vcloud\n    ip:\n/mg' existing

for i in ${ips[@]}
do
        echo inserting $i
        perl -pi -e "\$a=1 if(!\$a && s/ip:\n/ip: $i\n/);" existing
done
}


case "$1" in
  "" )
    beaker $BEAKEROPTS \
    --hosts $configYaml \
    --pre-suite $presuite \
  ;;

  --p* )
    echo "$0 Preserving hosts and saving hostnames."
    beaker $BEAKEROPTS \
    --hosts $configYaml \
    --pre-suite $presuite \
    --preserve-hosts | \
    tee .beakerlog.log

    echo "$0 checking contents of .beakerlog.log"
    for h in $(grep "Using available host" .beakerlog.log | grep -oE "[0-9a-z]{15}.delivery.puppetlabs.net" )
      do
      echo "\t$0 found host $h.  Storing in .beakerhosts"
      echo $h >> .beakerhosts
      done
  ;;

  --r*|--u* )
    echo "$0 Looking for hosts to reuse in .beakerhosts"
    # if .beakerhosts exists and has two entries we can proceed
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

