# Puppet Server Acceptance Testing

This directory is intended to manage acceptance testing for the Puppet Server.

## Acceptance Testing for Dummies

The workflow described here is intended for developers who have access to the
[vmpooler](http://vmpooler.delivery.puppetlabs.net), which currently requires
[VPN access](https://confluence.puppetlabs.com/display/HELP/VPN+access) and as
such is only applicable to Puppet Labs employees. This workflow is intended to
help developers new to the puppetserver project.

The two primary goals of this workflow are to enable the developer to modify
the behavior of acceptance tests in as little time as possible and to operate
in a manner as similar to the production CI system as possible.

If you would like to setup local VMs to develop and exercise acceptance tests,
or would like to learn about additional options beyond the vmpooler
configuration, please see the [README_LOCAL_VM.md](README_LOCAL_VM.md)
document.

-------------------------------------------------------------------------------

**Important:** The commands described in this document assume you are
  working at the root of the puppetserver repo, that is, in the
  directory that contains the acceptance directory.

-------------------------------------------------------------------------------

## Select VM(s)

VMs are already prepared and available in the vmpooler. For a list of all
platforms available in the pool, please see the output of:

    curl --url vmpooler.delivery.puppetlabs.net/vm

## Define PACKAGE_BUILD_VERSION and PUPPET_BUILD_VERSION environment variables

You'll need to provide a couple of environment variables that specify against
which builds of puppetserver and puppet-agent to install and
test. You can test an existing build or test a new build with local
changes.

### PACKAGE_BUILD_VERSION when testing an existing build

1. Go to http://builds.delivery.puppetlabs.net/puppetserver/?C=M;O=D
2. This puts the most recent build at the top of the screen. It should
   look something like: `2.2.2.master.SNAPSHOT.2016.02.18T1627`
3. Copy the text (not the link address) and set `PACKAGE_BUILD_VERSION` to
   to this value.

### PACKAGE_BUILD_VERSION when testing a new build with local changes

1. Run
   ```
   lein clean
   lein install
   lein with-profile ezbake ezbake build
   ```
   This will take a while, and
   will result in a build appearing on http://builds.delivery.puppetlabs.net/puppetserver.
2. If successful, it will give you back a URL with the new puppetserver
   version at the end, which will look something like `2.2.2.master.SNAPSHOT.2016.02.18T1627`.
   Set `PACKAGE_BUILD_VERSION` to this value.

### PUPPET_BUILD_VERSION

Define `PUPPET_BUILD_VERSION` as the packaged version of the puppet-agent package
upon which your version of puppetserver depends, such as 1.3.5. You can pick
a puppet-agent version from http://builds.delivery.puppetlabs.net/puppet-agent/?C=M;O=D.

If you are testing backwards compatibility against a specific release of
Puppet, the `PUPPET_LEGACY_VERSION` environment variable is available for this
purpose, but is not required.

### Install dependencies

Beaker needs to be installed and is expressed as a Gemfile
dependency. To install beaker, simply run `bundle install --path
.bundle/gems` from the repo's root directory. Running beaker will then
be a matter of saying `bundle exec beaker ...` as described later or
using the handy shell script created for your testing convenience. 

## Running beaker directly

To run beaker, you need a host configuration file. Configurations we
actively test reside in `acceptance/config/beaker/jenkins`. You can use
one of the existing config files or you can create your own config
file using `beaker-hostgenerator`. Unless stated otherwise,
discussion in this document assumes the content of
`acceptance/config/beaker/jenkins/redhat7-64m-64a.cfg`.

### Using an existing config file

To use one of the pre-defined config files, specify the path to it
using `beaker`'s `--config` option. For example:

    bundle exec beaker --config acceptance/config/beaker/jenkins/redhat7-64m-64a.cfg ...

### Using beaker-hostgenerator

Beaker config files can be generated with the `beaker-hostgenerator`
command available in the `beaker-hostgenerator` gem maintained by
QE. Generating a new config file takes the form

    bundle exec beaker-hostgenerator LAYOUT_STRING > snowflake.cfg

For example, to duplicate the config in `redhat7-64m-64a.cfg`, use the
following command:

    bundle exec beaker-hostgenerator redhat7-64m-64a > redhat7-64m-64.cfg

The syntax for `LAYOUT_STRING` is a bit strange and irregular. Start
with the output of `bundle exec beaker-hostgenerator --help`.
[beaker-hostgenerator source](https://github.com/puppetlabs/beaker-hostgenerator)
might also be helpful.

### Executing beaker

After choosing or creating the host configuration file, you can invoke
beaker. The best way to run beaker is to look at one of the existing
Jenkins CI jobs that are running in the pipeline you care about and
adapting the beaker invocation for local execution on your
workstation. Working with Jenkins remains an exercise for the reader.

Here is an example beaker command line that runs a single test:

    export PACKAGE_BUILD_VERSION='2.1.0.SNAPSHOT.2015.04.17T0057'
    export PUPPET_BUILD_VERSION='1.0.0'
    bundle exec beaker \
      --debug \
      --root-keys \
      --no-color \
      --repo-proxy \
      --preserve-hosts never \
      --type aio \
      --helper acceptance/lib/helper.rb \
      --options-file acceptance/config/beaker/options.rb \
      --load-path acceptance/lib \
      --config acceptance/config/beaker/jenkins/redhat7-64m-64a.cfg \
      --keyfile ~/.ssh/id_rsa-acceptance \
      --pre-suite acceptance/suites/pre_suite/foss \
      --tests acceptance/suites/tests/00_smoke/testtest.rb

This command will kick off a beaker run using vmpooler against the
hosts defined in the copy of the config file
`acceptance/config/beaker/jenkins/redhat7-64m-64a.cfg`. It will run
all the pre-suite steps and then the simple "no op" `testtest.rb` in
puppetserver/acceptance.

If the run fails during a pre-suite step, you might want to re-run the
command with `--preserve-hosts onfail` so that beaker doesn't return
the VMs to the pool for recycling. This will allow you to SSH into the
target VMs and troubleshoot the issue.

-------------------------------------------------------------------------------

**Note:** You may also need to obtain the `~/.ssh/id_rsa-acceptance`
file from a colleague so that beaker can SSH into the target VM's
located in the pooler.

**Note:** [VPN access](https://confluence.puppetlabs.com/display/HELP/VPN+access)
is required when executing tests against vmpooler.

-------------------------------------------------------------------------------

### Iterative Development

In general, the overhead of running the pre-suite is worth the price
of admission to the VM pooler system. For the one-time cost of the
time spent running the pre-suite to configure a test environment, you
don't have to maintain your own local VMs, you can reuse the
configured VMs with minimal effort, and then just throw them away when
you're done or need a fresh environment.

To operate against the same set of VMs over and over again, run
through the above workflow the _first_ time, using `--preserve-hosts
always` instead of `--preserve-hosts never`. This will keep the hosts
from being handed back to vmpooler when the test completes.

Next, make a copy of the `log/latest/hosts_preserved.yml` file:

    cp log/latest/hosts_preserved.yml .

This file is a fully populated beaker configuration file based on the
barebones configuration you provided. Then, when you want to run
another round of tests against those hosts, use this slightly modified
beaker call:

    export PACKAGE_BUILD_VERSION='2.1.0.SNAPSHOT.2015.04.17T0057'
    export PUPPET_BUILD_VERSION='1.0.0'
    bundle exec beaker \
      --debug \
      --root-keys \
      --no-color \
      --repo-proxy \
      --preserve-hosts always \
      --type aio \
      --helper acceptance/lib/helper.rb \
      --options-file acceptance/config/beaker/options.rb \
      --load-path acceptance/lib \
      --config hosts_preserved.yml \
      --keyfile ~/.ssh/id_rsa-acceptance \
      --no-provision \
      --tests acceptance/suites/tests/00_smoke/testtest.rb

The differences here are that instead of specifying a pre-suite to
execute, the command instead uses `--no-provision` to prevent Beaker
from trying to (re)configure the already configured hosts. It also
specifies the copied hosts file, `hosts_preserved.yml` so that beaker
knows which hosts to use. 

## Using the handy shell script
If all that seems like too much work, we have a script that should
make things much simpler for you. It relies on the built-in defaults
for all arguments. All you provide is the two environment variables
`PACKAGE_BUILD_VERSION` and `PUPPET_BUILD_VERSION`. Thus:

1. Set the environment variables `PUPPET_BUILD_VERSION` and
   `PACKAGE_BUILD_VERSION` as described previously.
2. Execute `acceptance/scripts/generic/testrun.sh -p` to start a
   complete run of the tests and preserve the hosts. When the tests
   complete, the script makes a copy of the (modified) config file as
   `hosts_preserved.yml` at the top level of the repo.

```
   export PACKAGE_BUILD_VERSION='2.1.0.SNAPSHOT.2015.04.17T0057'
   export PUPPET_BUILD_VERSION='1.0.0'
   acceptance/scripts/generic/testrun.sh -p
```
   
3. To reuse the hosts saved from a previous run, specify `-r` instead
   of `-p`, that is, `acceptance/scripts/generic/testrun.sh -r`.

