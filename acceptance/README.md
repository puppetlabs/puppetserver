# JVM Puppet Acceptance Testing

This directory is intended to manage acceptance testing for the JVM Puppet
Master.

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
* Modify /etc/sysonfig/network (HOSTNAME=centos6-64-1.local)
* Adds /et/sysconfig/network-scripts/ifcfg-eth1
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

## Workflow

Below are some suggested workflows that ought to help getting started.

### #1: Pure Beaker

If you are just running the tests contained in this repository, it is possible
to simply install beaker and run it, passing all the necessary configuration
through the command line:

    bundle install --path vendor/bundle
    bundle exec beaker -c ./acceptance/config/beaker/vbox/el6/64/1host.cfg --type foss --debug --fail-mode slow --pre-suite ./acceptance/suites/pre_suite --helper ./acceptance/lib/helper.rb --tests ./acceptance/suites/tests

### #2: Rakefile

The Rakefile in jvm-puppet top-level directory is used during acceptance testing
in the Jenkins CI pipeline to run various types of acceptance test in a
consistent and reliable fashion. There are at least two expected workflows, one
where BEAKER_CONFIG and JVMPUPPET_REPO_CONFIG are set manually prior to running the
acceptance rake task, and another where values for those variables are
calculated based on the values of other variables (see Workflow b below).

#### Rakefile Workflow a: Local vSphere Hypervisor

This is the expected workflow for running vcloud tests directly from one's
laptop.

    bundle install --path vendor/bundle
    export BEAKER_CONFIG=./acceptance/config/beaker/jenkins/debian-7-i386.cfg
    export JVMPUPPET_REPO_CONFIG=http://builds.puppetlabs.lan/jvm-puppet/0.1.2.SNAPSHOT.2014.05.12T1408/repo_configs/deb/pl-jvm-puppet-0.1.2.SNAPSHOT.2014.05.12T1408-wheezy.list
    export BEAKER_OPTS="--keyfile /home/username/downloads/id_rsa-acceptance"
    bundle exec rake test:acceptance:beaker 

Noteworthy here is BEAKER_OPTS which specifies an acceptance testing specific
private key used to communicate with vsphere hosts. Without this, you will see
repeated connection refused errors in Beaker's output.

#### Rakefile Workflow b: "Jenkins" vSphere Hypervisor

The following is a workflow that duplicates what happens in 'Workflow a' above
by using PACKAGE_BUILD_NAME, PACKAGE_BUILD_VERSION, PLATFORM, and ARCH to
produce the same values of BEAKER_CONFIG and JVMPUPPET_REPO_CONFIG as seen above
in a ruby function. It is primarily intended for use in Jenkins
acceptance/integration test jobs.

    bundle install --path vendor/bundle
    export PACKAGE_BUILD_NAME=jvm-puppet
    export PACKAGE_BUILD_VERSION=0.1.2.SNAPSHOT.2014.05.12T1408
    export PLATFORM=debian-7
    export ARCH=i386
    export BEAKER_OPTS="--keyfile /home/username/downloads/id_rsa-acceptance"
    bundle exec rake test:acceptance:beaker 

PACKAGE_BUILD_NAME and PACKAGE_BUILD_VERSION are build parameters available as
environment variables in the Jenkins 'execute shell script' build step.

PLATFORM and ARCH are matrix parameters set for acceptance test Jenkins jobs.
They are also available within the 'execute shell script' build step as
environment variables.

#### Rakefile Workflow c: Local Vagrant Hypervisor

The following workflow is intended to demonstrate running on a local machine
using a vagrant hypervisor.

    bundle install --path vendor/bundle
    export BEAKER_CONFIG=./acceptance/config/beaker/jenkins/debian-7-i386.cfg
    export JVMPUPPET_REPO_CONFIG=http://builds.puppetlabs.lan/jvm-puppet/0.1.2.SNAPSHOT.2014.05.12T1408/repo_configs/deb/pl-jvm-puppet-0.1.2.SNAPSHOT.2014.05.12T1408-wheezy.list

### Environment Variables
The following is a list of environment variables are supported by the
test:acceptance:beaker Rake task and descriptions of the effect each has.

* $JVMPUPPET_REPO_CONFIG 
  * Default: None, fail loudly if no JVMPUPPET_REPO_CONFIG available.
  * Description: This variable is used by the Beaker pre_suite to obtain a
  package repository configuration file used by yum or apt on the System Under
  Test. It is expected that this url will become available for a particular
  version of JVM Puppet as a result of an ezbake/packaging run.

* $BEAKER_CONFIG 
  * Beaker CLI Option: -c 
  * Default: None, fail loudly if no BEAKER_CONFIG available.
  * Description: Same as the Beaker option.

* $BEAKER_TYPE 
  * Beaker CLI Option: --type 
  * Default: foss 
  * Description: Same as the Beaker option.

* $BEAKER_FAILMODE 
  * Beaker CLI Option: --fail-mode 
  * Default: slow 
  * Description: Same as the Beaker option.

* $BEAKER_PRESUITE 
  * Beaker CLI Option: --pre-suite 
  * Default: ./acceptance/suites/pre_suite 
  * Description: Same as the Beaker option.

* $BEAKER_LOADPATH 
  * Beaker CLI Option: --load-path 
  * Default: 
  * Description: Same as the Beaker option.

* $BEAKER_HELPER 
  * Beaker CLI Option: --helper 
  * Default: ./acceptance/lib/helper.rb 
  * Description: Same as the Beaker option.

* $DEBUG 
  * Beaker CLI Option: --helper 
  * Default:  
  * Description: Any nonempty string will cause Beaker to be run in debug mode.

