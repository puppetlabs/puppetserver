# Puppet Server Acceptance Testing

This directory is intended to manage acceptance testing for the Puppet Server.

## Acceptance Testing for Dummies

The workflow described here is intended for developers who have access to the
[vmpooler](http://vmpooler.delivery.puppetlabs.net), which currently requires
[VPN access](https://confluence.puppetlabs.com/display/HELP/VPN+access) and as
such is only applicable to Puppet employees. This workflow is intended to
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

## Define environment variables

You'll need to provide environment variables that specify against
which builds of puppetserver and (optionally) puppet-agent to install and
test. You can test an existing build or test a new build with local
changes.

### PACKAGE_BUILD_VERSION when testing an existing build

1. Go to http://builds.delivery.puppetlabs.net/puppetserver/?C=M;O=D to find the
   most recent build, or copy the value of the `PACKAGE_BUILD_VERSION` parameter
   from a jenkins job you're trying to reproduce.
2. Set `PACKAGE_BUILD_VERSION` to this value. It should look something like:
   `6.15.1.SNAPSHOT.2021.01.29T0152`

### PACKAGE_BUILD_VERSION when testing a new build with local changes

1. Run
   ```
   lein clean
   lein install
   EZBAKE_ALLOW_UNREPRODUCIBLE_BUILDS=true EZBAKE_NODEPLOY=true JENKINS_USER_AUTH='<your.username>:<your jenkins-platform token>' lein with-profile ezbake,provided ezbake build
   ```
   This will take a while, and
   will result in a build appearing on http://builds.delivery.puppetlabs.net/puppetserver.
2. If successful, it will give you back a URL with the new puppetserver
   version at the end, which will look something like `6.15.1.SNAPSHOT.2021.01.29T0152`.
   Set `PACKAGE_BUILD_VERSION` to this value.

### PUPPET_BUILD_VERSION (optional)

Define `PUPPET_BUILD_VERSION` as the version of the puppet-agent package upon
which your version of puppetserver depends. This can be a tag or SHA.
Unless you're making changes to the agent specifically, you probably won't need
to set this value. By default, we test against the version defined in the
[beaker options](https://github.com/puppetlabs/puppetserver/blob/6.x/acceptance/config/beaker/options.rb#L14) file.

### PUPPET_LEGACY_VERSION (optional)

If you are testing backwards compatibility against a specific release of
Puppet, the `PUPPET_LEGACY_VERSION` environment variable is available for this
purpose, but is not required.

## Using the handy shell script

We have a script that will run beaker tests for you, relying on the built-in
defaults for all arguments. All you provide is the `PACKAGE_BUILD_VERSION`
environment variable. Thus:

  ```
   PACKAGE_BUILD_VERSION='6.15.1.SNAPSHOT.2021.01.29T0152' acceptance/scripts/generic/testrun.sh -p
  ```

Running with `-p` will preserve the hosts and leave a `hosts_preserved.yml`
at the top level of the repo.

To reuse the hosts saved from a previous run, specify `-r` instead of `-p`,
that is, `acceptance/scripts/generic/testrun.sh -r`.

See [the script](https://github.com/puppetlabs/puppetserver/blob/6.x/acceptance/scripts/generic/testrun.sh#L41-L50) for additional environment variables you can set and their defaults.

-------------------------------------------------------------------------------

**Note:** You may need to obtain the `~/.ssh/id_rsa-acceptance`
file from a colleague so that beaker can SSH into the target VM's
located in the pooler.

**Note:** [VPN access](https://confluence.puppetlabs.com/display/HELP/VPN+access)
is required when executing tests against vmpooler.

-------------------------------------------------------------------------------
