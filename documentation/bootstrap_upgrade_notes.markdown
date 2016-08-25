---
layout: default
title: "Puppet Server: Bootstrap Upgrade Notes"
canonical: "/puppetserver/latest/bootstrap_upgrade_notes.html"
---

[`bootstrap.cfg` file]: https://docs.puppet.com/puppetserver/2.4/external_ca_configuration.html#disabling-the-internal-puppet-ca-service

> **Note:** If you have disabled the certificate authority (CA) services on a Puppet Server master running Puppet Server 2.4.x or earlier, please read this before upgrading the master to Puppet Server 2.5.0. If you haven't, or you don't manage or modify your [`bootstrap.cfg` file][], consult the rest of the [Puppet Server 2.5.0 release notes](https://docs.puppet.com/puppetserver/2.5/release_notes.html).

Users of Puppet Server 2.4.x and earlier could modify their [`bootstrap.cfg` file][] in order to disable the CA on compile masters and support a multi-master configuration. Upgrades between these older versions have been painful, however, due to package managers attempting to overwrite this file during upgrades.

This could cause two problems:

1.  If users disabled CA services and chose the packaged version during the upgrade, CA services would be re-enabled on the master after the upgrade, which could break their multi-master setup.

2.  If users disabled CA services and chose their version of `bootstrap.cfg`, and the new version contained settings for new services that were added to the packaged version of `bootstrap.cfg`, and in that case, the server will fail to start.

Puppet Server 2.5.0 takes the first steps toward resolving this problem while maintaining configurability by changing how service bootstrap configuration works. However, users of Puppet Server 2.4.x and older who disabled the CA service in `bootstrap.cfg` **must take special precautions when upgrading to 2.5.0** to prevent the upgrade process from re-enabling the CA service or potentially overwriting files in the `ssldir`. (Subsequent releases should no longer be subject to this issue.)

## Upgrading to 2.5.0 or newer

Puppet Server 2.5.0 no longer uses the [`bootstrap.cfg` file][] to configure service bootstrapping. Instead, it reads files within the `/etc/puppetlabs/puppetserver/services.d/` directory, which can contain multiple files --- some designed to be edited by users --- that configure service bootstrapping.

If you edited or manage your `bootstrap.cfg` file, do the following:

### Before you upgrade: `ca.cfg`

> **Warning:** Back up your masters' [`ssldir`](https://docs.puppet.com/puppet/latest/reference/dirs_ssldir.html) (or at least your `crl.pem` file) before the upgrade. If a master unexpectedly enables CA services or an emergency rollback overwrites your certificates and certificate revocation list, you'll need to restore them from backups.

Puppet Server 2.5 creates a new configuration file, `/etc/puppetlabs/puppetserver/services.d/ca.cfg`, if it doesn't already exist, and this new file enables CA services by default.

To ensure that CA services remain disabled after upgrading, create the `/etc/puppetlabs/puppetserver/services.d/ca.cfg` file with contents that disable the CA services _before_ you upgrade to Server 2.5.0. Unlike the `boostrap.cfg` file, package managers **do not** overwrite the new `ca.cfg` file, allowing future upgrades to respect settings without attempting to overwrite them.

This example `ca.cfg` file disables the CA services:

```
# To enable the CA service, leave the following line uncommented
#puppetlabs.services.ca.certificate-authority-service/certificate-authority-service
# To disable the CA service, comment out the above line and uncomment the line below
puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
```

### After you upgrade: New bootstrap configuration files

Starting in Puppet Server 2.5.0, the `bootstrap.cfg` file has been split into multiple configuration files in two locations:

-   `/etc/puppetlabs/puppetserver/services.d/`: For services that users are expected to edit.
-   `/opt/puppetlabs/server/apps/puppetserver/config/services.d/`: For services users _shouldn't_ edit.

Any files with a `.cfg` extension in either of these locations are combined to form the final set of services Puppet Server will use.

The CA-related configuration settings previously in `bootstrap.cfg` are set in `/etc/puppetlabs/puppetserver/services.d/ca.cfg`. If services added in future versions have user-configurable settings, the configuration files will be in this directory. When upgrading Puppet Server 2.5.0 and newer with a package manager, it should not overwrite files already in this directory.

The remaining services are configured in `/opt/puppetlabs/server/apps/puppetserver/config/services.d/bootstrap.cfg`. This allows us to create and enforce default configuration files for other services across upgrades.
