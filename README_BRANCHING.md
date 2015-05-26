This document attempts to capture the details of our branching strategy
for Puppet Server.  This information is up-to-date as of 2015-05-21.

## Puppet Server Branches

Similar to most of the other projects at Puppet Labs, there should generally
only be two branches that are relevant at all in the puppetlabs github repo:
`master` and `stable`.

In the case of Puppet Server, the `stable` branch maps to the 1.x series of
OSS Puppet Server releases.  These releases are compatible with Puppet 3.x.

The `master` branch maps to the 2.x series of OSS Puppet Server releases.  The
2.0 release is compatible with Puppet 4.x as part of Puppet Collection 1 (PC1).
The forthcoming 2.1 release and later are intended to be compatible with Puppet
3.x and Puppet 4.x.  Puppet 3.x is supported for remote agents only, only Puppet
4.x satisfies the package dependency between Puppet Server and Puppet.

## Important Notes About Upcoming Releases

At the time of this writing, we're preparing for the release of OSS 1.1.0 and
2.1.0.  The two releases are intended to be roughly equivalent to each other
in terms of functionality, with the primary differences being around support
for Puppet 4.x compatibility in Puppet Server 2.1.  The 2.1.0 release will
include a URL compatibility layer that will allow Puppet 3.x agents to talk to
Puppet Server 2.x.  For more info, see https://tickets.puppetlabs.com/browse/SERVER-526.

The `stable` and `master` branches are essentially frozen with the exception of
any critical fixes which would otherwise block delivery of the 1.1 and/or
2.1 releases.

In summary:

 * `stable` is for work to be done toward the 1.1 release.
 * `master` is for work to be done toward the 2.1 release.

## "Normal" branching workflow

Under normal circumstances, the `stable` branch will be used for bugfix releases
off of the last stable "Y" release.  PRs should only be targeted at stable if
they are intended to go out in a z/bugfix release, and all code merged into the
stable branch should be merged up to the master branch at regular intervals.

Most PRs should normally be targeted at the `master` branch, which is where
feature work will be going on for the next X/Y release.

Ideally, as soon as a new x/major release is shipped (e.g. Puppet Server 2.0),
that would become the stable branch and there would be no additional planned 1.x
releases.  Because of the drastic nature of the changes between Puppet 3 and
Puppet 4, it's possible that we'll need to continue to do both 1.x and 2.x
releases of Puppet Server for some period of time.
