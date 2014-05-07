# JVM Puppet Acceptance Testing

This directory is intended to manage acceptance testing for the JVM Puppet
Master.

## Hypervisors

This acceptance testing suite has so far only been configured to work with
VirtualBox using Beaker/Vagrant. To add support for additional Vagrant boxes or
hypervisors, add Beaker config files to the './config/beaker' directory.  
  
The default Beaker config is ./config/beaker/vbox/el6/64/1host.cfg. This value
can be overridden with the BEAKER_CONFIG environment variable.

### Vagrant
Example of running this setup can be found in **./bin/examples/localrun_vagrant.sh**   
Supporting this is the beaker config found in **./config/vbox/el6/64/1host.cfg**   
  
In this setup Beaker uses Vagrant to provision your VMs. It probably requires
the least amount of effort to get running.

#### Local Static/Virtualbox

Example of running this setup can be found in **./bin/examples/localrun-static.sh**  
Supporting this is the beaker config found in **./config/local/el6/1host.cfg**  
  
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

Example of running this setup can be found in **./bin/examples/vsphere.sh**  
Supporting this is the beaker config found in **./config/beaker/vcenter_mono.ch**
or **.config/beaker/vcenter_dual.cfg**  
  
This setup requires you to pass the "--keyfile <path/to/id_rsa>" that will
allow you to log in to to the vSphere machine. There is a somewhat promiscuous
Acceptance testing private key, if you need it please ask someone in QA or QE.

## Workflow

Below are some suggested workflows that ought to help getting started.

### #1: Pure Beaker

If you are just running the tests contained in this repository, it is possible
to simply install beaker and run it, passing all the necessary configuration
through the command line:

    bundle install --path vendor/bundle
    bundle exec beaker -c ./config/beaker/vbox/el6/64/1host.cfg --type "${BEAKER_TYPE:-foss --debug --fail-mode slow --pre-suite ./suites/pre_suite --helper ./lib/helper.rb --tests ./suites/tests

There isn't really a simple workflow for dealing with external acceptance tests.
See the Shell Script setup section below for a way to handle that. In fact, the
Shell Script workflow is recommended.

### #2: Shell Scripts

This acceptance test suite comes with a shell script that aims to be useful for
both command-line invocations of the acceptance testing suites during normal
development and in Continuous Integration scripts. This is
'./bin/jvm-acceptance.sh'.

This script takes care of tasks like adding a "JVMPUPPET_REPO_CONFIG" key to the
"options" file hash to make the value of JVMPUPPET_REPO_CONFIG available during
Beaker's TestCases. Below is an example using this script:

    bundle install --path vendor/bundle
    export JVMPUPPET_REPO_CONFIG=http://int-resources.ops.puppetlabs.net/chris/jvm-puppet/internal_releases/0.1.0/repo_configs/rpm/pl-jvm-puppet-0.1.0-el-6-x86_64.repo
    ./bin/jvm-acceptance.sh

#### Environment Variables
The following is a list of environment variables are supported by
'./bin/jvm-acceptance.sh' and descriptions of the effect each has.


* $JVMPUPPET_REPO_CONFIG 
  * Description: './bin/jvm-acceptance.sh' will fail gracefully if this variable
is not set externally. This variable should point to some location that is
accessible via wget or curl from the Puppet Master host.

* $EXTERNAL_ACCEPTANCE_REPO 
  * Description: If this variable is set before running,
'./bin/jvm-acceptance.sh' will git clone from it into a temporary directory to
make the acceptance tests available for this test run. The default behavior in
this regard is simply to not use an external acceptance test suite. All the
important BEAKER_* variables default to the correct paths for jvm-puppet
specific acceptance testing.

* $EXTERNAL_ACCEPTANCE_REF 
  * Description: With this variable set, the external acceptance testing
repository can be set to any arbitrary commit to allow concisely specifying
which tests need to be run. If this variable is not set, then
'./bin/jvm-acceptance.sh' will populate it with the contents of
./config/param/EXTERNAL_ACCEPTANCE_REF. The use of this file allows the external
acceptance repository commit to be asynchronously pinned to the jvm-puppet
repository.

* $EXTERNAL_ACCEPTANCE_TESTSUITE 
  * Default: acceptance/tests
  * Description: This variable is used relative to the cloned
$EXTERNAL_ACCEPTANCE_REPO and is used as the value of the "tests" option passed
to Beaker on it's command line call.

* $EXTERNAL_ACCEPTANCE_LOADPATH 
  * Default: acceptance/lib
  * Description: This variable is used relative to the cloned
$EXTERNAL_ACCEPTANCE_REPO and is used as the $BEAKER_LOADPATH value (see below).

* $BEAKER_CONFIG 
  * Beaker CLI Option: -c 
  * Default: ./config/beaker/vbox/el6/64/1host.cfg 
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
  * Default: ./suites/pre_suite 
  * Description: Same as the Beaker option.

* $BEAKER_LOADPATH 
  * Beaker CLI Option: --load-path 
  * Default: 
  * Description: Same as the Beaker option.

* $BEAKER_HELPER 
  * Beaker CLI Option: --helper 
  * Default: ./lib/helper.rb 
  * Description: Same as the Beaker option.

* $DEBUG 
  * Beaker CLI Option: --helper 
  * Default:  
  * Description: Any nonempty string will cause Beaker to be run in debug mode.

* $@ 
  * Description: All positional parameters passed to './bin/jvm-acceptance.sh'
get passed as positional parameters to Beaker.

