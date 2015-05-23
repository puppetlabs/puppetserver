# Puppet Server Acceptance Testing

This directory is intended to manage acceptance testing for the Puppet Server.

## Acceptance Testing for Dummies

The workflow described here is intended for developers who have access to the
[vmpooler](http://vmpooler.delivery.puppetlabs.net), which currently requires
[VPN Access](https://confluence.puppetlabs.com/display/HELP/VPN+access) and as
such is only applicable to Puppet Labs employees.  This workflow is intended to
help developers new to the puppet-server project.

The two primary goals of this workflow are to enable the developer to modify
the behavior of acceptance tests in as little time as possible while operating
in a manner as similar to the production CI system as possible.

If you would like to setup local VM's to develop and exercise acceptance tests,
or would like to learn about additional options beyond the vmpooler
configuration, please see the [README_LOCAL_VM.md](README_LOCAL_VM.md)
document.

#### Prepare the VM

No action is required here, VM's are already prepared and available in the vm
pool.  For a list of all platforms prepared by the pool, please see the output
of:

    curl --url vmpooler.delivery.puppetlabs.net/vm

#### Define a hosts config file for your new VM

Copy/modify the local RHEL-7 host config at
`acceptance/config/beaker/jenkins/redhat7-64m-64a.cfg`  This file configures a
basic 2-node configuration.

Please note, the most recent host configurations can be generated with the
`genconfig` command available from the `sqa-utils` gem.  Generating a new host
configuration takes the form of `bundle exec genconfig redhat7-64ma`

The host expression is a bit strange and is parsed as per the documentation at
`bundle exec genconfig --help`.  See also [the
source][genconfig].

[genconfig]: https://github.com/puppetlabs/sqa-utils-gem/blob/76d8dbc/lib/genconfig/cli.rb#L20-L47

#### Define the PACKAGE_BUILD_VERSION and PUPPET_VERSION environment variables

You'll need to provide a couple environment variables that specify which build
of puppet-server to install and test against.

1. Go to http://builds.puppetlabs.lan/puppetserver/
2. Scroll down to the most recent build at the bottom.  This will look like:
   `0.1.4.SNAPSHOT.2014.05.15T1118`
3. Copy the text (not the link address) - this will be `PACKAGE_BUILD_VERSION`
4. Define ```PUPPET_VERSION``` as the packaged version of the puppet-agent
   package that we're depending upon, which is currently `1.1.0`

If you are testing backwards compaitiblity against a specific release of
puppet, the `PUPPET_LEGACY_VERSION` environment variable is also available for
this purpose, but is not required.

#### Install dependencies

Beaker needs to be installed and is expressed as a Gemfile dependency.  To
install beaker, simply run `bundle install --path .bundle/gems` from the top
level directory of the project.

Running beaker will then be a matter of `bundle exec beaker ...`

#### Run Beaker

In general, the best way to run beaker is to look at one of the existing
Jenkins CI jobs that are running in the pipeline you care about and adapting
the beaker invocation to run locally from your workstation.

You may also need to obtain the `~/.ssh/id\_rsa-acceptance` file from a
colleague so that beaker can SSH into the target VM's located in the pooler.

[VPN access](https://confluence.puppetlabs.com/display/HELP/VPN+access) is
required when executing tests against the VM pooler.

There is an example script that runs only a subset of tests in
`dev/scripts/server545_puppet3_compatibility.sh`

    #!/bin/bash
    export PACKAGE_BUILD_VERSION='2.1.0.SNAPSHOT.2015.04.17T0057'
    export PUPPET_VERSION='1.0.0'
    export PUPPET_LEGACY_VERSION='3.7.5'
    bundle exec beaker \
      --debug \
      --root-keys \
      --no-color \
      --repo-proxy \
      --preserve-hosts never \
      --type aio \
      --config acceptance/config/beaker/jenkins/redhat7-64m-64a.yaml \
      --pre-suite acceptance/suites/pre_suite/foss \
      --tests acceptance/suites/tests/00_smoke/testtest.rb \
      --keyfile ~/.ssh/id_rsa-acceptance \
      --helper acceptance/lib/helper.rb \
      --options-file acceptance/config/beaker/options.rb \
      --load-path acceptance/lib

This command will kick off a beaker run against a VM running in the pooler that
will run all the pre_suite steps and then the simple "no op" testtest.rb that
we have in puppet-server/acceptance.

If the run fails during a pre-suite step, you may want to re-run the command
with `--preserve-hosts onfail` so that beaker doesn't return the VM to the pool
for recycling.  This will allow you to SSH into the target VM and troubleshoot
the issue.

## Iterative Development

In general, the overhead of running the pre-suite is worth the price of
admission to the VM pooler system.  In previous iterations of this workflow
local VM snapshots were utilized to avoid the overhead of the pre-suite step.
With the introduction of the pooler it's generally the case that the pre-suite
time is worth the savings gained from not having to maintain local VM's.

A handy trick if you want to operate against the same VM over and over again is
to run through the above workflow with `--preserve-hosts always`, then iterate
against the same host borrowed from the pool in a slightly modified host
configuration.

For example, to run beaker multiple times againast the same borrowed VM:

    HOSTS:
      jafi7etcwfzovhd.delivery.puppetlabs.net:
        roles:
          - master
          - agent
        platform: el-7-x86_64
        pe_dir:
        pe_ver:
        pe_upgrade_dir:
        pe_upgrade_ver:
    CONFIG:
      nfs_server: none
      consoleport: 443
