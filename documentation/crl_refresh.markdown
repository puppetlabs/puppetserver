---
layout: default
title: "Puppet Server: Automatic CRL refresh"
canonical: "/puppetserver/latest/crl_refresh.html"
---

Starting in version 2.8.0, Puppet Server can automatically reload an updated CRL into the running SSL context, so that the revocation of an agent's certificate no longer requires a restart of the service to take effect. Prior versions required an explicit restart or reload of this service to reload the CRL, resulting in some small amount of downtime to effect the revocation of a certificate. With automatic CRL refresh enabled, revocation is now transparent and requires no service downtime.

### Enabling

To enable automatic CRL refresh, modify your Puppet Server services bootstrap configuration file to include the following line:

`puppetlabs.trapperkeeper.services.watcher.filesystem-watch-service/filesystem-watch-service`

This line should already exist, commented out, in the default ca.cfg shipped in Puppet Server 2.8.0, but if you are upgrading and have modified your ca.cfg, adding this line manually may be required. See [Service Bootstraping](./configuration.markdown#service-bootstrapping) for information on how to update your Puppet Server's services bootstrap configuration.

### Java Compatiblity

_This feature is only recommended for systems running Java 8._ It exhibited some instability when Puppet Server was running on systems running Java 7. We believe this is possibly due to bugs and platform-specific behavior in the underlying Java file system watcher implementation that were addressed in Java 8 but not in Java 7.

### Implementation

Automatic CRL refresh leverages the the [trapperkeeper file system watcher](https://github.com/puppetlabs/trapperkeeper-filesystem-watcher) to watch for changes to the CRL file, and loads the updated CRL on change.

### Contributors

Thanks to Jeremy Barlow, who laid the groundwork for this feature in Puppet Server.