---
layout: default
title: "Puppet Server: Automatic CRL refresh"
canonical: "/puppetserver/latest/crl_refresh.html"
---

Starting in version 5.1.0, Puppet Server can automatically reload an updated CRL into the running SSL context, so that the revocation of an agent's certificate no longer requires a restart of the service to take effect. Prior versions required an explicit restart or reload of this service to reload the CRL, resulting in some small amount of downtime to effect the revocation of a certificate. Revocation is now transparent and requires no service downtime.

If you are upgrading and have modified your ca.cfg, adding the following line manually may be required. See [Service Bootstraping](./configuration.markdown#service-bootstrapping) for information on how to update your Puppet Server's services bootstrap configuration.

`puppetlabs.trapperkeeper.services.watcher.filesystem-watch-service/filesystem-watch-service`

### Implementation

Automatic CRL refresh leverages the the [trapperkeeper file system watcher](https://github.com/puppetlabs/trapperkeeper-filesystem-watcher) to watch for changes to the CRL file, and loads the updated CRL on change.

### Contributors

Thanks to Jeremy Barlow, who laid the groundwork for this feature in Puppet Server.
