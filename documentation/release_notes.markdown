---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

[layout]: https://github.com/puppetlabs/puppet-specifications/blob/2818c90163837ae6a45eb070cf9f6edfb39a1e3f/file_paths.md
[pup4install]: /puppet/latest/reference/install_linux.html
[semver]: http://semver.org/

## Puppet Server 2.1

Release June 2, 2015.

This release contains several new features, improvements, and bug fixes. In keeping with [semantic versioning][semver] practices, this release of Puppet Server introduces changes that contain new backwards compatible functionality.

### Supported Platforms

Puppet Server 2.1 ships for the following supported platforms:

 * Enterprise Linux 7
 * Enterprise Linux 6
 * Ubuntu 14.04
 * Ubuntu 12.04
 * Debian 7

### What's New in Puppet Server 2.1

#### Backwards Compatibility with Puppet 3

Puppet Server 2.1 now supports both Puppet 4 and Puppet 3 agents. This is new functionality compared to Puppet Server 2.0 which only supported Puppet 4 agents.

Please see [Compatibility with Puppet Agent](./compatibility_with_puppet_agent.markdown) for more information.

#### New JRuby Pool features

##### Flush JRuby after max-requests reached

Puppet Server 2.1 introduces a new feature that allows the JRuby containers and associated threads to be flushed and re-initialized after a configurable number of HTTP requests.  This functionality is similar to the PassengerMaxRequests functionality that is often tuned in Puppet+Passenger configurations.

More info:

* [Tuning Guide](./tuning_guide.markdown)
* [SERVER-325](https://tickets.puppetlabs.com/browse/SERVER-325)

##### Other JRuby Pool Related Items

* [SERVER-246](https://tickets.puppetlabs.com/browse/SERVER-246) - Add :borrow-timeout config option.
* [SERVER-324](https://tickets.puppetlabs.com/browse/SERVER-324) - Added support to the `environment-cache` API for flushing an environment by name, as opposed to only having the ability to flush all environments.
* [SERVER-389](https://tickets.puppetlabs.com/browse/SERVER-389) - Increase JRuby pool default-borrow-timeout.
* [SERVER-391](https://tickets.puppetlabs.com/browse/SERVER-391) - Improve error message for JRuby pool borrow timeout.
* [SERVER-408](https://tickets.puppetlabs.com/browse/SERVER-408) - Expose configurable `borrow-timeout` to allow JRuby pool borrows to timeout.
* [SERVER-448](https://tickets.puppetlabs.com/browse/SERVER-448) - Change default max-active-instances to not exceed 4 JRubies.

#### REST auth.conf

As a result of the REST API URL changes between Puppet Server 1.x and 2.0, Puppet Server 2.1 users who have modified their `auth.conf` will need to make changes when using Puppet 3 agents with Puppet Server 2.1.  Please see [Compatibility with Puppet Agent](./compatibility_with_puppet_agent.markdown) for more information.

These auth.conf changes follow the same changes required for any users upgrading from Puppet 3.x to Puppet 4.x.

### Maintenance & Small Improvements

* [SERVER-380](https://tickets.puppetlabs.com/browse/SERVER-380) - Upgrade Jetty to 9.2.x.
* [SERVER-437](https://tickets.puppetlabs.com/browse/SERVER-437) - Upgrade stable branch to jvm-ssl-utils 0.8.0.
* [SERVER-517](https://tickets.puppetlabs.com/browse/SERVER-517) - Re-raise HttpClientExceptions as Ruby SocketError from http-client handler.
* [SERVER-518](https://tickets.puppetlabs.com/browse/SERVER-518) - Removed the hyphen from "puppet-server" in the project.clj in order to preserve backward compatibility with dujour check-ins of Puppet Server which previously used "puppetserver" as the artifact-id. The group-id also changed from "puppetlabs.packages" to "puppetlabs".
* [SERVER-530](https://tickets.puppetlabs.com/browse/SERVER-530) - Merge up stable branch from 1.0.8 release commit to 2.1 branch.
* [SERVER-544](https://tickets.puppetlabs.com/browse/SERVER-544) - Reduced the amount of memory used by the master to cache the payload for incoming catalog requests.
* [SERVER-598](https://tickets.puppetlabs.com/browse/SERVER-598) - To improve ability to introspect routes, we ported Puppet Server to use comidi for web routes.
* [SERVER-599](https://tickets.puppetlabs.com/browse/SERVER-599) - Long-running memory test that covers latest JRuby changes in stable.
* [SERVER-614](https://tickets.puppetlabs.com/browse/SERVER-614) - Fix set_connect_timeout_milliseconds error when sending report to PuppetDB.
* [SERVER-680](https://tickets.puppetlabs.com/browse/SERVER-680) - Upgraded JRuby dependency to 1.7.20 in order to take advantage of some of the memory management improvements we’ve seen in our internal testing.

### Misc Bug Fixes

* [SERVER-157](http://tickets.puppetlabs.com/browse/SERVER-157) - Utilize keylength puppet conf value for generating SSL cert keys.
* [SERVER-273](http://tickets.puppetlabs.com/browse/SERVER-273) - Upgrade to JRuby 1.7.19 / fix for jruby-pool DELETE memory leak.
* [SERVER-345](http://tickets.puppetlabs.com/browse/SERVER-345) - Fixup usages of cacert / localcacert in master.
* [SERVER-404](http://tickets.puppetlabs.com/browse/SERVER-404) - Properly create /var/run/puppetserver dir at service startup.
* [SERVER-442](http://tickets.puppetlabs.com/browse/SERVER-442) - Fix for a problem where `file_metadatas` requests to the master which include multiple `ignore` parameters were being mishandled. This had previously led to an agent downloading files from the master which should have been ignored.
* [SERVER-535](http://tickets.puppetlabs.com/browse/SERVER-535) - Tests in the `jruby-puppet-service-test` namespace were using an invalid configuration map, which caused them to throw an error.
* [SERVER-541](http://tickets.puppetlabs.com/browse/SERVER-541) - Disabled the display of verbose output that appeared during a package upgrade.
* [SERVER-564](http://tickets.puppetlabs.com/browse/SERVER-564) - Re-enabled the master `status` endpoint
* [SERVER-647](http://tickets.puppetlabs.com/browse/SERVER-647) - Puppet Server failed to start with with an uncaught exception when master-{code, run, log}-dir settings were not defined. This patch addresses the problem by setting the values to nil, which will cause Puppet Server to consult Puppet for the values.
* [SERVER-657](http://tickets.puppetlabs.com/browse/SERVER-657) - Fixed `puppetserver foreground` on Ubuntu.
* [SERVER-655](http://tickets.puppetlabs.com/browse/SERVER-655) - Upgrade to JRuby 1.7.20 caused server fail loading the Bouncy Castle jar.
* [SERVER-659](http://tickets.puppetlabs.com/browse/SERVER-659) - Restored broken http client timeout settings.
* [SERVER-682](http://tickets.puppetlabs.com/browse/SERVER-682) - Fixed an issue where logback levels weren’t changed unless you restarted Puppet Server. This functionality had been provided in Puppet Server 1.0.2 but was inadvertently removed in Puppet Server 1.0.8, then merged with 2.0.
* [SERVER-683](http://tickets.puppetlabs.com/browse/SERVER-683) - Changed the logic in the `legacy-routes-service` to get the route of the master-service via the service protocol name rather than by a hard-coded service name. This allows for the `legacy-routes-service` to pull the route from whatever service implementing the MasterService protocol happens to be in the service stack, i.e., master-service for open source Puppet or pe-master-service for PE.
* [SERVER-684](http://tickets.puppetlabs.com/browse/SERVER-684) - Append `source_permissions=use` to 3.x file_metadata requests.


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

