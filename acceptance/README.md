# puppet server Acceptance Testing

This directory is intended to manage acceptance testing for the Puppet Server.

This document describes how to run acceptance _locally_ using VMs provided by vmpooler. If you would like to use _local VMs_ to
develop and exercise acceptance tests, or would like to learn about additional
options beyond the vmpooler configuration, please see the
[README\_LOCAL\_VM.md](README_LOCAL_VM.md) document.

The workflow described here is intended for developers who have access to the
[vmpooler](http://vmpooler.delivery.puppetlabs.net), which currently requires
[VPN Access](https://confluence.puppetlabs.com/display/HELP/VPN+access) and as
such is only applicable to Puppet Labs employees. The primary audience is
developers and other Puppet technical staff new to the puppet-server project.

The two primary goals of this workflow are:

1. Enable developers to modify the behavior of acceptance tests in as little time as possible.
1. Operate in a mannger and environment as similar to the production CI system as possible.

Unless otherwise indicated, all commands assume you are working in the top-level `puppet-server` directory

## Acceptance Testing Using vmpooler

VMs are already prepared and available in the VM pool. For a list of all platforms prepared by the pool, please see the output
of:

    curl --url vmpooler.delivery.puppetlabs.net/vm

## Install dependencies

Beaker needs to be installed and is expressed as a Gemfile dependency.  To
install beaker, simply run `bundle install --path .bundle/gems` from the top
level directory of the project.

Running beaker will then be a matter of using `bundle exec beaker ...` as described
below.

You might also need to obtain the `~/.ssh/id\_rsa-acceptance` file from a
colleague so that beaker can SSH into the target VMs located in the pooler.

### Define your test environment using environment variables

You'll need to provide two environment variables that specify which builds of the
agent and server to install and test against, ```PACKAGE_BUILD_VERSION``` and
`PUPPET_VERSION`. ```PACKAGE_BUILD_VERSION``` indicates
the build of the puppet-server package to install.

1. Go to [http://builds.puppetlabs.lan/puppetserver/?C=M;O=D](http://builds.puppetlabs.lan/puppetserver/?C=M;O=D).
1. This puts the most recent build at the top of the display. It will look like `2016.1.1004.SNAPSHOT.2016.02.03T1410`.
1. This value (the text, not the link address) is ```PACKAGE_BUILD_VERSION```.
1. `PUPPET_VERSION` specifies which version of the puppet-agent package to install.
This is currently `1.3.4`.

If you are testing backwards compaitiblity against a specific release of
puppet, the ```PUPPET_LEGACY_VERSION``` environment variable is also available for
this purpose, but is not required.

### Run Beaker from a script

In the simplest case, after you have set ```PACKAGE_BUILD_VERSION``` and
`PUPPET_VERSION`, you can use the script `acceptance/scripts/generic/testrun.sh`,
which is easier to use than invoking beaker directly. As always, YMMV.

      export PACKAGE_BUILD_VERSION=2.2.2.master.SNAPSHOT.2016.02.02T1619
      export PUPPET_VERSION=1.3.5
      acceptance/scripts/generic/testrun.sh -p

The `-p` argument tells the script to tell Beaker to preserve the VM hosts
after testing completes rather than returning them to the pooler. This leaves
the provisioned and installed VMs ready for further testing. It uses the Beaker
hosts file.

### Define a hosts config file for your new VM

If you want more control than `testrun.sh` provides, you can define your own
Beaker hosts file. Copy/modify the RHEL7 host config at
`acceptance/config/beaker/jenkins/redhat7-64m-64a.cfg`. It creates a
basic, 2-node master-agent configuration.

Please note, the most recent host configurations can be generated with the
`beaker-hostgenerator` command available from the `beaker-hostgenerator` gem,
which will be installed from puppet-server's Gemfile, described below. To
generate an equivalent configuration to the one described above, use the
following command:

    `bundle exec beaker-hostgenerator redhat6-64ma-ubuntu1404-64a-windows2008r2-64a`

The host syntax is a bit strange and is parsed as per the documentation at
`bundle exec beaker-hostgenerator --help`.  See also [the source](https://github.com/puppetlabs/beaker-hostgenerator).

[beaker-hostgenerator]: https://github.com/puppetlabs/beaker-hostgenerator

### Run Beaker directly

In general, the best way to run beaker is to look at one of the existing
Jenkins CI jobs that are running in the pipeline you care about and adapting
the beaker invocation to run locally from your workstation.

You may also need to obtain the `~/.ssh/id\_rsa-acceptance` file from a
colleague so that beaker can SSH into the target VM's located in the pooler.

[VPN access](https://confluence.puppetlabs.com/display/HELP/VPN+access) is
required when executing tests against the VM pooler.

Here is an example Beaker invocation that you can modify for you own uses

    export PACKAGE_BUILD_VERSION='2.1.0.SNAPSHOT.2015.04.17T0057'
    export PUPPET_VERSION='1.0.0'
    export PUPPET_LEGACY_VERSION='3.7.5'
    bundle exec beaker \
      --debug \
      --root-keys \
      --no-color \
      --repo-proxy \
      --preserve-hosts always \
      --type aio \
      --config acceptance/config/beaker/jenkins/redhat7-64m-64a.yaml \
      --pre-suite acceptance/suites/pre_suite/foss \
      --keyfile ~/.ssh/id_rsa-acceptance \
      --helper acceptance/lib/helper.rb \
      --options-file acceptance/config/beaker/options.rb \
      --load-path acceptance/lib \
      --tests acceptance/suites/tests/00_smoke/testtest.rb

This command will kick off a beaker run against a VM running in the pooler that
will run all the pre-suite steps and then the simple "no op" test file,
`testtest.rb`, that we have in puppet-server/acceptance.

If the run fails during a pre-suite step, you may want to re-run the command
with `--preserve-hosts onfail` so that beaker doesn't return the VM to the pool
for recycling.  This will allow you to SSH into the target VM and troubleshoot
the issue.

### Iterative Development

In general, the overhead of running the pre-suite is worth the price of
admission to the VM pooler system. It's generally the case that the pre-suite
time is worth the savings gained from not having to maintain local VM's.

A handy trick if you want to operate against the same VM over and over again is
to run through the above workflow with `--preserve-hosts always`, then iterate
against the same host borrowed from the pool in a slightly modified host
configuratijon.

For example, to run beaker multiple times againast the same borrowed VM, replace _HOSTNAME_ in the hosts fail with the VM's FQDN as returned buy the pooler. For example,
if your VM hostnames are `orwell.delivery.puppetlabs.net` and `lovecraft.delivery.puppetlabs.net`:

    ---
    HOSTS:
      orwell.delivery.puppetlab.net:
        roles:
          - agent
          - master
        hypervisor: vcloud
        pe_dir:
        pe_ver:
        pe_upgrade_dir:
        pe_upgrade_ver:
        platform: el-7-x86_64
        template: Delivery/Quality Assurance/Templates/vCloud/redhat-7-x86_64
      lovecraft.delivery.puppetlabs.net:
        roles:
        - agent
        hypervisor: vcloud
        pe_dir:
        pe_ver:
        pe_upgrade_dir:
        pe_upgrade_ver:
        platform: el-7-x86_64
        template: Delivery/Quality Assurance/Templates/vCloud/redhat-7-x86_64
    CONFIG:
      nfs_server: none
      consoleport: 443
      datastore: instance0
      folder: Delivery/Quality Assurance/Enterprise/Dynamic
      resourcepool: delivery/Quality Assurance/Enterprise/Dynamic
      pooling_api: http://vcloud.delivery.puppetlabs.net/
