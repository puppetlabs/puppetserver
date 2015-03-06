#!/bin/bash
# look in http://builds.puppetlabs.lan/puppetserver
export PACKAGE_BUILD_VERSION=2.0.0.SNAPSHOT.2015.03.06T0120
export PUPPET_VERSION=3.7.4

BEAKER_KEYFILE=$HOME/.ssh/id_rsa-acceptance
BEAKER_LOADPATH=lib
BEAKER_OPTIONSFILE=config/beaker/options.rb
BEAKER_HELPER=lib/helper.rb
BEAKER_PRESUITE=suites/pre_suite/foss
BEAKER_TESTSUITE=suites/tests/00_smoke/
BEAKER_POSTSUITE=suites/post_suite

#TODO: change this to aio in the near future.
BEAKER_TYPE=foss

UPGRADE_FROM=NONE
unset IS_PE

BEAKER_HOSTSCONFIG=config/beaker/jenkins/redhat-7-x86_64-mdca.cfg
# BEAKER_HOSTSCONFIG=config/beaker/jenkins/redhat-6-x86_64-mdca.cfg

export GEM_SOURCE=http://rubygems.delivery.puppetlabs.net
GEM_SOURCE=http://rubygems.delivery.puppetlabs.net

pwd
while [ ! -d $BEAKER_LOADPATH ] && [ "$(pwd)" != "/" ]
  do
  cd ..
  echo "Now at $(pwd)" 
done

##########

mkBeakerHostConfig () {
cp $BEAKER_HOSTSCONFIG existing

perl -pi -e 's/hypervisor: vcloud\n/hypervisor: vcloud\n    ip:\n/mg' existing

for i in ${ips[@]}
  do
  echo inserting $i
  perl -pi -e "\$a=1 if(!\$a && s/ip:\n/ip: $i\n/);" existing
  done
  #TODO: Validate that we run out of ips in our array at the same time that we run out of blanks, the throw an error if not.
}

##########

case "$1" in 
        --p* )
        echo "$0 Preserving hosts and saving hostnames."
        beaker \
        --debug \
        --keyfile $BEAKER_KEYFILE \
        --load-path $BEAKER_LOADPATH \
        --config $BEAKER_HOSTSCONFIG \
        --helper $BEAKER_HELPER \
        --pre-suite $BEAKER_PRESUITE \
        --tests $BEAKER_TESTSUITE \
        --post-suite $BEAKER_POSTSUITE \
        --type $BEAKER_TYPE \
        --repo-proxy \
        --root-keys \
        --preserve-hosts | \
        tee .beakerlog.log

        echo "$0 checking contents of .beakerlog.log"
        for h in $(grep "Using available vCloud host" .beakerlog.log | grep -oE "[0-9a-z]{15}.delivery.puppetlabs.net" )
                do
                echo "\t$0 found host $h.  Storing in .beakerhosts"
                echo $h >> .beakerhosts
                done
        ;;

        --r*|--u* )
        # Reuse the existing hosts.  This is used for script development.
        echo "$0 Looking for hosts to reuse in .beakerhosts"
        ips=()
        for h in $(cat .beakerhosts)
            do
              echo $h
              ips+=( $h )
            done
        mkBeakerHostConfig ${ips[@]}
        ~/git/beaker/bin/beaker \
        --debug \
        --keyfile $BEAKER_KEYFILE \
        --load-path $BEAKER_LOADPATH \
        --config existing \
        --tests $BEAKER_TESTSUITE \
        --post-suite $BEAKER_POSTSUITE \
        --no-provision \
        --preserve-hosts \
        ;;

        * )
        echo "$1 is not a valid parameter"
        echo "usage:"
        echo "$0 \[--preserve | --useExisting\]"
        echo "\t --p --preserve : Launches beaker with --preserve-hosts option set.  Stores resulting hosts for later use."
        echo "\t --r --u --useExisting: looks for hosts in .beakerhosts: launches beaker with options to use existing hosts."
        ;;

esac


