---
layout: default
title: "Puppet Server: Backward Compatibility with Puppet 3 Agents"
canonical: "/puppetserver/latest/deprecated_settings.md"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[new `auth.conf`]: ./conf_file_auth.html
[Puppet `auth.conf`]: /puppet/latest/reference/config_file_auth.html

> **Note:** If you're upgrading to Puppet Server 2.2, take these deprecated features into consideration. Also, consult our documentation of [compatibility with Puppet agent versions](./compatibility_with_puppet_agent.html).

The following Puppet Server settings are deprecated in Puppet Server 2.2 and will be removed in a future version.

## Ruby Authorization

Puppet Server 2.2 introduces a new authorization method based on [`trapperkeeper-authorization`][]. This new method implements new authorization settings and configuration syntax, and improves support for authorizing requests to Puppet Server administration and certificate status endpoints. 

The legacy [Puppet `auth.conf`][] rules for authorizing access to master endpoints, and client whitelists for the Puppet admin and certificate status endpoints, are deprecated.

### Legacy `auth.conf` Rules and Settings

To configure the new authorization methods, Puppet Server 2.2 uses a new HOCON-formatted [`auth.conf`][new `auth.conf`] configuration file located by default in the new `confdir` location (`/etc/puppetlabs/puppetserver/conf.d`).

Puppet recommends converting your rules to the new HOCON format and enabling this new method. For details, see the [Puppet Server configuration documentation](./configuration.html).

### `ca.conf` and `master.conf`

Puppet Server's new authorization methods deprecate the only settings in [`ca.conf`](./config_file_ca.html) and [`master.conf`](./config_file_master.html), and these files are no longer included by default in a new Puppet Server 2.2 installation. The settings configured in these files are now configured by new parameters in `auth.conf`.