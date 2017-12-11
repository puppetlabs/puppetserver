---
layout: default
title: "Puppet Server: Bootstrap Upgrade Notes"
canonical: "/puppetserver/latest/bootstrap_upgrade_notes.html"
---

[`bootstrap.cfg` file]: https://docs.puppet.com/puppetserver/2.4/external_ca_configuration.html#disabling-the-internal-puppet-ca-service

> ### Potential breaking issues when upgrading with a modified `bootstrap.cfg`
>
> If you disabled the certificate authority (CA) on Puppet Server by editing the [`bootstrap.cfg` file][] file on older versions of Puppet Server --- for instance, because you have a multi-master configuration with the default CA disabled on some masters, or use an external CA --- be aware that Puppet Server as of version 2.5.0 no longer uses the `bootstrap.cfg` file.
>
> Puppet Server 2.5.0 and newer instead create a new configuration file, `/etc/puppetlabs/puppetserver/services.d/ca.cfg`, if it doesn't already exist, and this new file enables CA services by default.
>
> To ensure that CA services remain disabled after upgrading, create the `/etc/puppetlabs/puppetserver/services.d/ca.cfg` file with contents that disable the CA services _before_ you upgrade to Server 2.5.0. The `puppetserver` service restarts after the upgrade if the service is running before the upgrade, and the service restart also reloads the new `ca.cfg` file.
>
> Also, back up your masters' [`ssldir`](https://puppet.com/docs/puppet/latest/dirs_ssldir.html) (or at least your `crl.pem` file) _before_ you upgrade to ensure that you can restore your previous certificates and certificate revocation list, so you can restore them in case any mistakes or failures to disable the CA services in `ca.cfg` lead to a master unexpectedly enabling CA services and overwriting them.

> ### Potential service failures when upgrading with a modified init configuration
>
> If you modified the init configuration file --- for instance, to [configure Puppet Server's JVM memory allocation](./install_from_packages.html#memory-allocation) or [maximum heap size](./tuning_guide.html) --- and upgrade Puppet Server 2.5.0 or newer with a package manager, you might see a warning during the upgrade that the updated package will overwrite the file (`/etc/sysconfig/puppetserver` in Red Hat and derivatives, or `/etc/default/puppetserver` in Debian-based systems).
>
> The changes to the file support the new service bootstrapping behaviors. If you don't accept changes to the file during the upgrade, the puppetserver service fails and you might see a `Service ':PoolManagerService' not found` or similar warning. To resolve the issue, set the `BOOTSTRAP_CONFIG` setting in the init configuration file to:
>
>     BOOTSTRAP_CONFIG="/etc/puppetlabs/puppetserver/services.d/,/opt/puppetlabs/server/apps/puppetserver/config/services.d/"
>
> If you modified other settings in the file before upgrading, and then overwrite the file during the upgrade, you might need to reapply those modifications after the upgrade.

Users of Puppet Server 2.4.x and earlier could modify their [`bootstrap.cfg` file][] in order to disable the CA on compile masters and support a multi-master configuration. Upgrades between these older versions have been painful, however, due to package managers attempting to overwrite this file during upgrades.

This could cause two problems:

1.  If users disabled CA services and chose the packaged version during the upgrade, CA services would be re-enabled on the master after the upgrade, which could break their multi-master setup.

2.  If users disabled CA services and chose their version of `bootstrap.cfg`, and the new version contained settings for new services that were added to the packaged version of `bootstrap.cfg`, and in that case, the server will fail to start.

Puppet Server 2.5.0 takes the first steps toward resolving this problem while maintaining configurability by changing how service bootstrap configuration works. However, users of Puppet Server 2.4.x and older who disabled the CA service in `bootstrap.cfg` **must take special precautions when upgrading to 2.5.0 or newer** to prevent the upgrade process from re-enabling the CA service or potentially overwriting files in the `ssldir`. (Subsequent releases should no longer be subject to this issue.)

## Upgrading to 2.5.0 or newer

Puppet Server 2.5.0 and newer no longer use the [`bootstrap.cfg` file][] to configure service bootstrapping. Instead, it reads files within the `/etc/puppetlabs/puppetserver/services.d/` directory, which can contain multiple files --- some designed to be edited by users --- that configure service bootstrapping.

If you edited or manage your `bootstrap.cfg` file, do the following:

### Before you upgrade: `ca.cfg`

> **Warning:** Back up your masters' [`ssldir`](https://puppet.com/docs/puppet/latest/dirs_ssldir.html) (or at least your `crl.pem` file) before the upgrade. If a master unexpectedly enables CA services or an emergency rollback overwrites your certificates and certificate revocation list, you'll need to restore them from backups.

Puppet Server 2.5 and newer create a new configuration file, `/etc/puppetlabs/puppetserver/services.d/ca.cfg`, if it doesn't already exist, and this new file enables CA services by default.

To ensure that CA services remain disabled after upgrading, create the `/etc/puppetlabs/puppetserver/services.d/ca.cfg` file with contents that disable the CA services _before_ you upgrade to Server 2.5.0 or newer. Unlike the `bootstrap.cfg` file, package managers **do not** overwrite the new `ca.cfg` file, allowing future upgrades to respect settings without attempting to overwrite them.

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
