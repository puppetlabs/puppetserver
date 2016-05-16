# Puppet Server Acceptance Testing

This directory is intended to manage acceptance testing for the Puppet Server.

## Acceptance Testing for Dummies

This setup is intended for developers when running a VM on their local machine.
This will not use Vagrant or a Rakefile, but instead a local pre-installed VM and the beaker CLI.

#### Prepare the VM

1. Get a fresh EL6 VM installed in VMWare or VirtualBox
   - One can be downloaded at: http://int-resources.ops.puppetlabs.net/pe-supported-virtual-machines/centos6-64.vmwarevm.tar.bz2
2. Install your SSH key on the VM so beaker can connect without authentication
3. Set the system clock to prevent issues such as SSL authentication
   - You'll need ntp or ntpdate and then run: ```ntpdate time.apple.com```
4. Take a snapshot of the VM now that you have a pristine OS with SSH access
   - You'll want to revert back to this snapshot every time the run fails during pre_suite
   - Note that **you might need to set the clock again after each VM restore**

#### Define a hosts config file for your new VM

1. Copy/modify the local EL6 host config at ./config/beaker/local/el6/1host.cfg to have the fully-qualified hostname and IP of your VM
   - Change the line at the top that looks like ```centos6-64-1.local:``` to the fully-qualified hostname of your VM
   - Change the ```ip``` value to your VM

#### Define the PACKAGE_BUILD_VERSION and PUPPET_VERSION environment variables

You'll need to provide a couple environment variables that specify which build of puppetserver to install and test against.

1. Go to http://builds.delivery.puppetlabs.net/puppetserver/
2. Scroll down to the most recent build at the bottom
   - This will look like: 0.1.4.SNAPSHOT.2014.05.15T1118
3. Copy the text (not the link address) - this will be ```PACKAGE_BUILD_VERSION```
4. Define ```PUPPET_VERSION``` as the packaged version of Puppet that we're building with, which is currently ```3.6.1```

#### Run Beaker

    export PACKAGE_BUILD_VERSION=<SEE PREVIOUS STEP; e.g. 0.1.4.SNAPSHOT.2014.05.15T1118>
    export PUPPET_VERSION=<SEE PREVIOUS STEP; e.g. 3.6.1>
    bundle install --path vendor/bundle
    bundle exec beaker --config <PATH TO YOUR HOSTS CONFIG FILE> --type foss --debug --fail-mode slow --helper ./acceptance/lib/helper.rb --load-path <PATH TO PUPPET REPO ON YOUR MACHINE>/acceptance/lib --options ./acceptance/config/beaker/options.rb --pre-suite ./acceptance/suites/pre_suite --tests ./acceptance/suites/tests

This should kick off a beaker run against your new VM that will run all the pre_suite steps and then the simple "no op" testtest.rb that we have in puppetserver/acceptance.

If the run fails during a pre-suite step, you'll need to revert your VM back to the previous state, resolve the error, and try again.
Otherwise, the next run will fail as the pre-suite steps assume a fresh machine and are not tolerant of existing installations.

When the run succeeds, you'll want to **take another snapshot of VM** so you can disable the pre-suite setup in subsequent runs for faster iteration.

#### Iterative Development & Debugging

Now that you've done a successful beaker run and taken a VM snapshot, it's time to run beaker again using the actual acceptance tests in the puppet repository.
It's important that you run the tests from the same version of Puppet that has been installed on your VM.

You can find the version that is installed by running ```puppet --version```.
Make sure that your cloned puppet repository on your machine is at the same version as above.
This probably just means a simple ```git checkout tags/<version>```.

Now you will want to run beaker again, but this time without the pre-suite argument and using the puppet acceptance tests.

    bundle exec beaker --config <PATH TO YOUR HOSTS CONFIG FILE> --type foss --debug --fail-mode slow --helper ./acceptance/lib/helper.rb --load-path <PATH TO PUPPET REPO ON YOUR MACHINE>/acceptance/lib --options ./acceptance/config/beaker/options.rb --tests <PATH TO PUPPET REPO ON YOUR MACHINE>/acceptance/tests

You should now be in a good state for running & debugging the acceptance tests.
When the tests fail, you may want to revert the VM back to the previous snapshot (the one you took right after a successful pre-suite setup) before you run them again.
This may be necessary because the tests can leave the machine in the same state it was in prior, and subsequent runs may be affected.

## Hypervisors

This acceptance testing suite has so far only been configured to work with
VirtualBox using Beaker/Vagrant. To add support for additional Vagrant boxes or
hypervisors, add Beaker config files to the './acceptance/config/beaker' directory.  
  
The default Beaker config is ./acceptance/config/beaker/vbox/el6/64/1host.cfg. This value
can be overridden with the BEAKER_CONFIG environment variable.

### Vagrant

An example of running acceptance tests using VirtualBox can be found in Rakefile
workflow 'c' below.  
Beaker configues supporting Virtualbox hypervisor can be found in subdirectories
of **./acceptance/config/vbox/**
  
In this setup Beaker uses Vagrant to provision your VMs. 

#### Local Static/Virtualbox

Below is an example of running this config:

    VBOX_MACHINE_NAME=PL-vmware-centos-64
    VBOX_STATE_NAME=savedstate
    
    VBoxManage controlvm "${VBOX_MACHINE_NAME:?}" poweroff
    
    VBoxManage snapshot "${VBOX_MACHINE_NAME:?}" restore "${VBOX_STATE_NAME:?}" && \
        VBoxManage startvm --type headless "${VBOX_MACHINE_NAME:?}" && \
        sleep 1 && \
        bundle exec rake test:acceptance:beaker

Beaker configs supporting static Virtualbox can be found in subdirectories of
**./acceptance/config/local/**
  
This setup is probably the most difficult to share between different systems.
However, it is pretty effective for fast iterative development of TestCases or
when addressing bugs in code that cause the TestCases to fail. Since there is
quite a bit of variability between each person's setup here is a brief outline
of what has worked in the past:

* Downloaded VMWARE image from  
http://int-resources.ops.puppetlabs.net/pe-supported-virtual-machines/centos6-64.vmwarevm.tar.bz2
* Setup Host-Only Networking  
https://confluence.puppetlabs.com/display/DEL/Create+a+Private+NAT+in+VirtualBox
* Install avahi, avahi-tools, lsof, man, openssh-server, curl, vim (actually
some of these aren't necessary, but they are nice).
* Add your public key to the VM's root authorized_keys
* Modify /etc/sysconfig/network (HOSTNAME=centos6-64-1.local)
* Adds /etc/sysconfig/network-scripts/ifcfg-eth1
* Modify /etc/ssh/sshd_config "UseDNS no"
* Modify /etc/rc.local to start /etc/init.d/messagebus and /etc/init.d/avahi-daemon
* Modify /etc/sudoers, comment out "requiretty" line
* Modify the aforementioned Beaker config to set the host's IP to point to
whatever is assigned this machine by dnsmasq.

It also assumes the host system has both dnsmasq and avahi-daemon running with
a local domain named ".local". Confluence wiki currently documents the dnsmasq
setup to use ".vm":  
https://confluence.puppetlabs.com/display/DEL/Create+a+Private+NAT+in+VirtualBox

Basically what needs to happen is, get your clean VM configured then save state
while it's running and configured for your host-only network. Then replace the
VBOX_MACHINE_NAME and VBOX_STATE_NAME with the names of your new CentOS6 vm and
its running saved state, respectively. 

### vSphere

Example of running acceptance tests in vSphere can be found in the Rakefile
workflows 'a' and 'b' below.  
  
Beaker configs supporting vsphere can be found in **./acceptance/config/beaker/jenkins/**
  
This setup requires you to pass the "--keyfile <path/to/id_rsa>" that will
allow you to log in to to the vSphere machine. There is a somewhat promiscuous
Acceptance testing private key, if you need it please ask someone in QA or QE.
It probably requires the least amount of effort to get this setup running.

## Install from `ezbake`

Installing from source can be accomplished without the need for
`PACKAGE_BUILD_VERSION` if `PUPPETSERVER_INSTALL_TYPE=git`. Roughly speaking, the
following events will occur when using this install type:

1. Run `lein install`
1. Clone ezbake to `./tmp/ezbake` or pull from origin if it's already there.
1. Use `PUPPETSERVER_VERSION` or `lein with-profile acceptance pprint version` to get
the current puppetserver development version. If `PUPPETSERVER_VERSION` is set in the
environment when Beaker is run, then this will be preferred and must refer to a
valid puppetserver version stored either in the local Maven repository or Nexus.
1. Change working directory to `./tmp/ezbake` and run `lein run stage puppetserver
puppetserver-version=VERSION` where VERSION is the version obtained in the
previous step.
1. Change working directory to `./tmp/ezbake/target/staging` and run `rake
package:bootstrap`. **Note** This step uses the locally installed version of
puppetserver installed in the first step.
1. Run `rake pl:print_build_param[ref]` to obtain the staging version number.
1. Run `rake package:tar` to build tarball with all necessary installation files.
1. Create user:group `puppet:puppet` on SUT.
1. Run `env prefix=/usr confdir=/etc rundir=/var/run/puppetserver
initdir=/etc/init.d make -e install-puppetserver`
1. Run `env prefix=/usr confdir=/etc rundir=/var/run/puppetserver
initdir=/etc/init.d make -e install-{rpm,deb}-sysv-init`

This is just an overview to give an idea of what is going on and how/why it
works or doesn't work. `PACKAGE_BUILD_VERSION` can be set but won't do anything in
this case.

## Workflow

Below are some suggested workflows that ought to help getting started.

### #1: Pure Beaker

If you are just running the tests contained in this repository, it is possible
to simply install beaker and run it, passing all the necessary configuration
through the command line:

    bundle install --path vendor/bundle
    bundle exec beaker -c ./acceptance/config/beaker/vbox/el6/64/1host.cfg --type foss --debug --fail-mode slow --pre-suite ./acceptance/suites/pre_suite --helper ./acceptance/lib/helper.rb --tests ./acceptance/suites/tests

### #2: Rakefile

The Rakefile in puppetserver top-level directory is used during acceptance testing
in the Jenkins CI pipeline to run various types of acceptance test in a
consistent and reliable fashion. There are at least two expected workflows, one
where BEAKER_CONFIG and PACKAGE_BUILD_VERSION are set manually prior to running the
acceptance rake task, and another where values for those variables are
calculated based on the values of other variables (see Workflow b below).

#### Rakefile Workflow a: Local vSphere Hypervisor

This is the expected workflow for running vcloud tests directly from one's
laptop.

    bundle install --path vendor/bundle
    export BEAKER_CONFIG=./acceptance/config/beaker/jenkins/debian-7-x86_64-mdca.cfg
    export PACKAGE_BUILD_VERSION=2.0.0.SNAPSHOT.2015.01.05T1228
    export BEAKER_OPTS="--keyfile /home/username/downloads/id_rsa-acceptance"
    export BEAKER_TYPE=foss
    export BEAKER_LOADPATH=./acceptance/lib
    bundle exec rake test:acceptance:beaker 

Noteworthy here is BEAKER_OPTS which specifies an acceptance testing specific
private key used to communicate with vsphere hosts. Without this, you will see
repeated connection refused errors in Beaker's output.

#### Rakefile Workflow b: "Jenkins" vSphere Hypervisor

The following is a workflow that duplicates what happens in 'Workflow a' above
by using PACKAGE_BUILD_NAME, PACKAGE_BUILD_VERSION, PLATFORM, and LAYOUT to
produce the same values of BEAKER_CONFIG and PACKAGE_BUILD_VERSION as seen above
in a ruby function. It is primarily intended for use in Jenkins
acceptance/integration test jobs.

    bundle install --path vendor/bundle
    export PACKAGE_BUILD_NAME=puppetserver
    export PACKAGE_BUILD_VERSION=0.1.2.SNAPSHOT.2014.05.12T1408
    export PLATFORM=debian-7
    export LAYOUT=i386
    export BEAKER_OPTS="--keyfile /home/username/downloads/id_rsa-acceptance"
    bundle exec rake test:acceptance:beaker 

PACKAGE_BUILD_NAME and PACKAGE_BUILD_VERSION are build parameters available as
environment variables in the Jenkins 'execute shell script' build step.

PLATFORM and LAYOUT are matrix parameters set for acceptance test Jenkins jobs.
They are also available within the 'execute shell script' build step as
environment variables.

#### Rakefile Workflow c: Local Vagrant Hypervisor

The following workflow is intended to demonstrate running on a local machine
using a vagrant hypervisor.

    bundle install --path vendor/bundle
    export BEAKER_CONFIG=./acceptance/config/beaker/vbox/el6/64/1host.cfg
    export PACKAGE_BUILD_VERSION=0.1.2.SNAPSHOT.2014.05.12T1408
	bundle exec rake test:acceptance:beaker

### Environment Variables
The following is a list of environment variables are supported by the
test:acceptance:beaker Rake task and descriptions of the effect each has.

* $PACKAGE_BUILD_VERSION
  * Default: None
  * Example: 0.1.2.SNAPSHOT.2014.05.12T1408
    export PACKAGE_BUILD_VERSION=0.1.2.SNAPSHOT.2014.05.12T1408
  * Description: This variable is used by the Beaker pre_suite to obtain a
  package repository configuration file used by yum or apt on the System Under
  Test. It is expected that this url will become available for a particular
  version of Puppet Server as a result of an ezbake/packaging run. **NOTE:** This variable is
  only necessary if `PUPPETSERVER_INSTALL_TYPE=package`, which is the default.

* $PUPPETSERVER_VERSION 
  * Default: None 
  * Valid: Any released/snapshotted `project.clj` puppetserver version.
  * Description: Determines the VALUE for `puppetserver-version=VALUE` when
  running Beaker with `PUPPETSERVER_INSTALL_TYPE=git`. For example if
  `PUPPETSERVER_VERSION=0.1.4-SNAPSHOT` and `PUPPETSERVER_INSTALL_TYPE=git` then the
  ezbake staging command run by the default pre_suite will look like `lein run
  stage puppetserver puppetserver-version=0.1.4-SNAPSHOT`

* $PUPPETSERVER_INSTALL_TYPE 
  * Default: package 
  * Valid: package, git 
  * Description: Determines whether Puppet Server will be installed from a
  pre-existing package or from the local git repository. This requires Java and
  leiningen to be installed.

* $BEAKER_CONFIG 
  * Beaker CLI Option: -c 
  * Default: None, fail loudly if no BEAKER_CONFIG available.
  * Description: Same as the Beaker option.

* $BEAKER_TYPE 
  * Beaker CLI Option: --type 
  * Default: None
  * Description: Same as the Beaker option.

* $BEAKER_FAILMODE 
  * Beaker CLI Option: --fail-mode 
  * Default: slow 
  * Description: Same as the Beaker option.

* $BEAKER_PRESUITE 
  * Beaker CLI Option: --pre-suite 
  * Default: ./acceptance/suites/pre_suite 
  * Description: Same as the Beaker option.

* $BEAKER_POSTSUITE 
  * Beaker CLI Option: --post-suite 
  * Default: ./acceptance/suites/post_suite 
  * Description: Same as the Beaker option.

* $BEAKER_LOADPATH 
  * Beaker CLI Option: --load-path 
  * Default: None
  * Description: Same as the Beaker option.

* $BEAKER_HELPER 
  * Beaker CLI Option: --helper 
  * Default: ./acceptance/lib/helper.rb 
  * Description: Same as the Beaker option.

* $DEBUG 
  * Beaker CLI Option: --helper 
  * Default:  
  * Description: Any nonempty string will cause Beaker to be run in debug mode.

