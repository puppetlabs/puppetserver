This document attempts to capture the details of our branching strategy
for Puppet Server.  This information is up-to-date as of 2015-04-29.

## Puppet Server Branches

Similar to most of the other projects at Puppet Labs, there should generally
only be two branches that are relevant at all in the puppetlabs github repo:
`master` and `stable`.

In the case of Puppet Server, the `stable` branch maps to the 1.x series of
OSS Puppet Server releases.  These releases are compatible with Puppet 3.x.

The `master` branch maps to the 2.x series of OSS Puppet Server releases.  The
2.0 release is compatible with Puppet 4.x as part of Puppet Collection 1 (PC1).
The 2.1 release and later are intended to be compatible with Puppet 3.x and
Puppet 4.x.  Puppet 3.x is supported for remote agents only, only Puppet 4.x
satisfies the package dependency between Puppet Server and Puppet.

## Important Notes About Upcoming Releases

At the time of this writing, we've just released OSS 2.0.0.  This release
should be considered roughly equivalent to the Puppet Server 1.0.2 release in
terms of functionality, and will largely only contain changes related to Puppet
4.0 compatibility.

There will be a quick turnaround for a 2.1 release following the 2.0 release.
This 2.1 release will be 2.0, plus the bugfixes in 1.0.8, plus a URL
compatibility layer that will allow Puppet 3.x agents to talk to Puppet Server
2.x (for more info see https://tickets.puppetlabs.com/browse/SERVER-526).
There may be a few other changes included in the 2.1 release but only on a
very limited basis.  A `2.1.x` branch has been created specifically for 2.1
development.

Some time following the release of 2.1, we'll do a 1.1 and 2.2 release,
hopefully close to one another.  These will be the next major feature releases
(as opposed to 2.0 and 2.1, which are mostly just compatibility releases), and 
will contain several new features, tuning improvements, etc.

As development toward the 1.1 and 2.1 releases proceeds, changes from the
`stable` and `2.1.x` branches, respectively, should be merged up to the `master`
branch.  Any changes that may be considered too risky or inappropriate to be
included in either the 1.1 or 2.1 releases could be targeted at the `master`
branch.

In summary:

 * `stable` is for work to be done toward the 1.1 release.
 * `2.1.x` is for work to be done toward the 2.1 release.
 * `master` is for work to be done toward the 2.2 release.

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
