---
layout: default
title: "Puppet Server 2.0: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

## Puppet Server 2.0

In keeping with [semantic versioning][semver] practices, this release
of Puppet Server introduces changes that break compatibility with
Puppet 3.x agents. Please carefully read these release notes before
attempting to upgrade your Puppet installation, as the locations of
key files have moved, and changes to the Puppet Server API may require
configuration changes.

### Supported Platforms

Puppet Server 2.0 ships for the following supported platforms:

 * Enterprise Linux 7
 * Enterprise Linux 6
 * Ubuntu 14.04
 * Ubuntu 12.04
 * Debian 7

### What's New in Puppet Server 2.0 

#### Repository Location and Pre-Installation

As with Puppet 4.0, Puppet Server 2.0 is now distributed via the new
Puppet Collection repositories. Puppet Server 2.0 is part of Puppet
Collection 1.

In order to install Puppet Server 2.0, you'll need to install a
release package appropriate to your operating system. See the
[Puppet 4 installation guide][pup4install] for information on how to
prepare your system for installation, and specific installation
instructions.

* [SERVER-524](https://tickets.puppetlabs.com/browse/SERVER-524) - Set
  repo-target to PC1 for 2.0 release

#### Changes to Library and File Locations

> **Note:** These changes affect your ability to upgrade
> agents. Please consult the [Puppet installation layout][layout]
> document for guidance on where to move files before upgrading.

As part of the move to a unified all-in-one configuration layout, the
locations of configuration files and directories has changed.

Due to changes in the location of Ruby gems in Puppet Server 2.0,
you'll need to move your SSL certificates and any extensions you've
installed, as well. 

* [SERVER-370](https://tickets.puppetlabs.com/browse/SERVER-370) -
  Change ruby-load-path to /opt/puppetlabs/puppet/lib/ruby/vendor_ruby
* [SERVER-371](https://tickets.puppetlabs.com/browse/SERVER-371) -
  Change gem_home to /opt/puppetlabs/puppet/cache/jruby-gems
* [SERVER-387](https://tickets.puppetlabs.com/browse/SERVER-387)-
  Update puppet-server config directory
* [SERVER-409](https://tickets.puppetlabs.com/browse/SERVER-409) -
  Plumb confdir, vardir, codedir, logdir, rundir values

#### REST auth.conf

As a result of the REST API URL changes between Puppet Server 1.x
and 2.0, Puppet Server 1.x users who have modified their `auth.conf` will
need to make changes when upgrading to Puppet Server 2.0.  See
[SERVER-526](https://tickets.puppetlabs.com/browse/SERVER-526) for
some additional information.

These auth.conf changes follow the same changes required for any users
upgrading from Puppet 3.x to Puppet 4.x. 

#### Backwards Compatibility

Puppet Server 2.0 is not backwards compatible with Puppet 3.x agents.
Version 2.0 of Puppet Server is only compatible with Puppet 4.x
agents. If upgrading all your agents and masters in concert will be a
problem for you, please consider waiting until we release Puppet
Server 2.1.

### Known Issues

#### Installing gems when Puppet Server is behind a proxy requires manual download of gems

When a Puppet master must access the Internet via a proxy server, it
is not possible to use the `puppetserver gem` command to install
gems. To work around this issue until we release a fix:

1. Use [rubygems.org](http://rubygems.org) to search for and download
   the gem you want to install.
2. Run the command `puppetserver gem install --local <PATH to GEM>`.

* [SERVER-377](https://tickets.puppetlabs.com/browse/SERVER-377) -
  `puppetserver gem` command doesn't work from behind a proxy server

### Bug Fixes

#### Puppet Server now reports when new versions are available.

Due to a mismatch in naming conventions, Puppet Server was unable to
report the availability of new versions. We've addressed this bug by
adopting the increasing preference to eliminate hyphens from assorted
project and package names.

* [SERVER-520](https://tickets.puppetlabs.com/browse/SERVER-520) -
  Apply artifact-id updates to version checks in master
* [SERVER-457](https://tickets.puppetlabs.com/browse/SERVER-457) - Get
  dujour working with Puppet Server 2.0.0 RC

#### Fix inconsistent behavior around `always_cache_features` setting

We've fixed a bug in puppetserver where always\_cache\_features was not always
overridden because it could be changed in puppet.conf. The behavior is
now explicitly managed in code rather than configuration.

* [SERVER-410](https://tickets.puppetlabs.com/browse/SERVER-410) -
  Explicitly override `always\_cache\_features` in puppetserver

#### Fix unreliable puppetserver start behavior

We've Fixed a bug where puppetserver was not starting reliably from
the service management framework due to the "rundir" not being
writable.

* [SERVER-414](https://tickets.puppetlabs.com/browse/SERVER-414) -
  Handle rundir creation for puppet and puppetserver in Puppet
  Server 2.x

#### Fix misleading silence from `puppetserver foreground` command 

We've fixed a bug where the puppetserver foreground command would not
produce any output, making it appear as if the command had not started
or was stalled. The foreground command produces debugging output
in 2.0.0.

* [SERVER-356](https://tickets.puppetlabs.com/browse/SERVER-356) -
  puppetserver foreground produces no output

### All Changes

For a list of all changes in this release, see this JIRA page:

* [All Puppet Server issues targeted at this release](https://tickets.puppetlabs.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%202.0.0%22%20ORDER%20BY%20updated%20DESC%2C%20priority%20DESC%2C%20created%20ASC)

[layout]: https://github.com/puppetlabs/puppet-specifications/blob/2818c90163837ae6a45eb070cf9f6edfb39a1e3f/file_paths.md
[current-install-docs]: /guides/install_puppet/install_el.html
[pup4install]: /puppet/4.0/reference/install_linux.html
[semver]: http://semver.org/
