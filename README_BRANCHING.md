This document attempts to capture the details of our branching strategy
for Puppet Server.  This information is up-to-date as of 2015-03-06.

## THE MOST IMPORTANT THING TO KNOW

tl;dr: we are putting a freeze on merging up changes from `stable` to `master`
until Puppet Server 2.0 ships.

## Puppet Server Branches

Similar to most of the other projects at Puppet Labs, there should generally
only be two branches that are relevant at all in the puppetlabs github repo:
`master`, and `stable`.

In the case of Puppet Server, the `stable` branch maps to the 1.x series of
OSS Puppet Server releases.  These releases are compatible with Puppet 3.x.

The `master` branch maps to the 2.x series of OSS Puppet Server releases.  These
releases are compatible with Puppet 4.x.

## Important Notes About Upcoming Releases

The next release of Puppet Server, temporally, will be version 2.0.  It will
be released in concert with the release of Puppet 4.0.  This release should be
considered roughly equivalent to the Puppet Server 1.0.2 release in terms of
functionality, and will largely only contain changes related to Puppet 4.0
compatibility.

Shortly following that, there will be an OSS 1.0.8 release.  This release will
be a bugfix release targeted for inclusion in PE 3.8.

Some time following that, we'll do a 1.1 and 2.1 release, hopefully in close
proximity to one another.  These will be the next major feature releases (as
opposed to 2.0, which is simply a compatibility release), and will contain
several new features, tuning improvements, etc.

The changes for 1.0.8 have started to land in the `stable` branch now.  Some of
them are too risky to introduce into the `master` branch given our proximity to
the 2.0 release.  Therefore, it is critical that we do *not* do any merges from
`stable` to `master` until after 2.0 has shipped.

We'll update this document to reflect changes to that restriction as things
progress.

## "Normal" branching workflow

Under normal circumstances, the `stable` branch will be used for bugfix releases
off of the last stable "Y" release.  PRs should only be targeted at stable if
they are intended to go out in a z/bugfix release, and all code merged into the
stable branch should be merged up to the master branch at regular intervals.

Most PRs should normally be targeted at the `master` branch, which is where feature
work will be going on for the next X/Y release.

Ideally, as soon as a new x/major release is shipped (e.g. Puppet Server 2.0), that
would become the stable branch and there would no additional planned 1.x releases.
Because of the drastic nature of the changes between Puppet 3 and Puppet 4, it's
possible that we'll need to continue to do both 1.x and 2.x releases of Puppet
Server for some period of time.  If we find ourselves in a situation where we
need three branches (e.g. a stable branch for 1.x and another stable branch for 2.x),
we may introduce a third branch (e.g. we might have `1.x`, `stable`->2.0.x, and
`master`->2.1.x, or something along those lines).  We will update this document as
the plans solidify.  

