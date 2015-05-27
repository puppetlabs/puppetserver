---
layout: default
title: "Puppet Server 2.0: Release Notes"
canonical: "/puppetserver/latest/release\_notes.html"
---

## Puppet Server 2.1

In keeping with [semantic versioning][semver] practices, this release of Puppet
Server introduces changes that contain new backwards compatible functionality.

### Supported Platforms

Puppet Server 2.1 ships for the following supported platforms:

 * Enterprise Linux 7
 * Enterprise Linux 6
 * Ubuntu 14.04
 * Ubuntu 12.04
 * Debian 7

### What's New in Puppet Server 2.1

#### Backwards Compatibility with Puppet 3

Puppet Server 2.1 now supports both Puppet 4 and Puppet 3 agents.  This is new
functionality compared to Puppet Server 2.0 which only supported Puppet 4
agents.

Please see [Compatibility with Puppet
Agent](./compatibility_with_puppet_agent.markdown) for more information.

#### Flush JRuby after max-requests reached

Puppet Server 2.1 introduces a new feature that allows the JRuby containers and
associated threads to be flushed and re-initialized after a configurable number
of HTTP requests.  This functionality is similar to the PassengerMaxRequests
functionality that is often tuned in Puppet+Passenger configurations.

Please see [SERVER-325](https://tickets.puppetlabs.com/browse/SERVER-325) and
the [Tuning Guide](./tuning_guide.markdown) for more information.

#### REST auth.conf

As a result of the REST API URL changes between Puppet Server 1.x and 2.0,
Puppet Server 2.1 users who have modified their `auth.conf` will need to make
changes when using Puppet 3 agents with Puppet Server 2.1.  Please see
[Compatibility with Puppet Agent](./compatibility_with_puppet_agent.markdown)
for more information.

These auth.conf changes follow the same changes required for any users
upgrading from Puppet 3.x to Puppet 4.x.

### Known Issues

#### TBA

### Bug Fixes

#### TBA

### All Changes

For a list of all changes in this release, see this JIRA page:

[layout]: https://github.com/puppetlabs/puppet-specifications/blob/2818c90163837ae6a45eb070cf9f6edfb39a1e3f/file_paths.md
[current-install-docs]: /guides/install_puppet/install_el.html
[pup4install]: /puppet/4.0/reference/install_linux.html
[semver]: http://semver.org/
