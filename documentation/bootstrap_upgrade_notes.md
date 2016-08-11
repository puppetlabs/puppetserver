# Bootstrap upgrade notes

**NOTE: If you have previously disabled your CA, please read this.**

In the past, puppetserver users who have modified their `bootstrap.cfg` file to disable the CA on their compile masters and support a multi-master setup have been subject to a painful upgrade experience.

Starting in puppetserver 2.5.0 this problem will start to be resolved. However, it's important to note that users will have to go through one final annoying upgrade if they're managing the CA service through their `bootstrap.cfg`. This page will walk you through what upgrading will be like if you are managing `bootstrap.cfg` yourself. 

# Upgrading to 2.5.0 or newer
If you are not managing your `bootstrap.cfg` file, you don't need to do anything.

If you are managing your `bootstrap.cfg`, here's what you need to be aware of:

## `ca.cfg`
puppetserver will no longer look at `/etc/puppetlabs/puppetserver/bootstrap.cfg` for bootstrap entries. If you have modified `bootstrap.cfg` to disable the CA service, you will have to edit `/etc/puppetlabs/puppetserver/services.d/ca.cfg` and disable it there.

It's a good idea to create `/etc/puppetlabs/puppetserver/services.d/ca.cfg` with the CA disabled *before* upgrading, since the puppetserver service will be restarted on upgrade if the service running when you upgrade. If you don't, the default `ca.cfg` created during the upgrade will re-enable the CA service.

# Background
Previously, during upgrades, if the new package contains any changes to `bootstrap.cfg`, the user would have to choose between their version of the file and the packaged version of the file. One of two bad things would happen:

1. If they have the CA disabled and choose the packaged version, their multi-master setup will be broken.
2. If they have the CA disabled and choose their version, they will be missing some potentially vital component of puppetserver, and it may fail to start.

The crux of the issue is that `bootstrap.cfg` currently contains some items we expect the user to change, and some we do not want them to ever change.

# The solution
Starting in puppetserver 2.5.0, the `bootstrap.cfg` file has been split into multiple .cfg files in two locations:
* `/etc/puppetlabs/puppetserver/services.d/` - Intended for services users are expected to edit
* `/opt/puppetlabs/server/apps/puppetserver/config/services.d/` - Intended for services users should not edit

Any `.cfg` files in these two locations are combined to form the final set of services puppetserver will use.

The CA related services from the old `bootstrap.cfg` have been moved to `/etc/puppetlabs/puppetserver/services.d/ca.cfg`

The remaining services have been moved to `/opt/puppetlabs/server/apps/puppetserver/config/services.d/bootstrap.cfg`

In the future, any new services that are intended to be modified by users can be be added in .cfg files along side `ca.cfg`, which will avoid upgrade conflicts. Similarly, the services internal to puppetserver can be modified however they need to be without affecting users.

