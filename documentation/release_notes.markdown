---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

[static catalogs]: /puppet/4.4/reference/static_catalogs.html
[file resources]: /puppet/4.4/reference/types/file.html
[Puppet catalogs]: /puppet/4.4/reference/subsystem_catalog_compilation.html
[environment classes API]: ./puppet-api/environment_classes.html
[classes]: /puppet/latest/reference/lang_classes.html
[resource type API]: /puppet/latest/reference/http_api/http_resource_type.html

## Puppet Server 2.3.0

Released March 16, 2016.

This is a feature release that adds functionality for static catalogs, a new environment classes API, and restarting Puppet Server with a HUP signal.

> ### New requirements
>
> Puppet Server 2.3 depends on [Puppet Agent 1.4.0](/puppet/4.4/reference/about_agent.html) or newer, which installs [Puppet 4.4](/puppet/4.4/) and compatible versions of its related tools and dependencies. For versions of Puppet Server compatible with Puppet Agent 1.3.x, we recommend the latest version of [Puppet Server 2.2](/puppetserver/2.2/).

### New feature: Static catalogs

Puppet Server 2.3.0 and Puppet 4.4.0 implement [static catalogs][], which inline metadata for [file resources][] into [Puppet catalogs][]. This improves the predictability of Puppet runs in workflows that use cached catalogs and file resources fetched from modules on a Puppet master.

* [SERVER-999](https://tickets.puppetlabs.com/browse/SERVER-999)

### New feature: `environment_classes` API

The [environment classes API][] in Puppet Server 2.3.0 serves as a replacement for the Puppet [resource type API][] when requesting information about [classes][] available to a Puppet Server.

* [SERVER-1110](https://tickets.puppetlabs.com/browse/SERVER-1110)

### New feature: Faster service restarts with HUP signals

Puppet Server 2.3.0 and newer support being restarted by sending a hangup signal, also known as [HUP or SIGHUP](./restarting.html), to the running Puppet Server process. You can send this signal to the Puppet Server process using the standard `kill` command. The HUP signal stops Puppet Server and reloads it gracefully, without terminating the JVM process. This is generally much faster than completely stopping and restarting the process, and allows you to quickly load changes to your Puppet Server master, including certain configuration changes.

* [SERVER-96](https://tickets.puppetlabs.com/browse/SERVER-96)

### Bug fix: Puppet Server correctly parses `config_version` script arguments

Previous versions of Puppet Server could fail when the `config_version` setting is a single string that contains spaces, which is commonly true when that setting points to a script with space-delineated arguments. Puppet Server 2.3.0 resolves this issue.

* [SERVER-1204](https://tickets.puppetlabs.com/browse/SERVER-1204)

### Known issues

#### Modifications to `bootstrap.cfg` might cause problems during upgrades to 2.3.0

If you modified `bootstrap.cfg` (for instance, to [enable or disable the Certificate Authority service](./configuration.html#service-bootstrapping)), upgrading to Puppet Server 2.3.0 from earlier versions of Puppet Server might fail. For instance, on Red Hat-family distributions of Linux, you might see a warning during the package update:

```
warning: /etc/puppetlabs/puppetserver/bootstrap.cfg created as /etc/puppetlabs/puppetserver/bootstrap.cfg.rpmnew
```

To resolve this, add this line to your `bootstrap.cfg`:

```
puppetlabs.services.versioned-code-service.versioned-code-service/versioned-code-service
```

Alternatively, you can merge your changes into the new version of `bootstrap.cfg` (the `bootstrap.cfg.rpmnew` file in the above example) and replace `bootstrap.cfg` with the new file.

* [SERVER-1058](https://tickets.puppetlabs.com/browse/SERVER-1058)

### All Changes

* [All Puppet Server issues targeted at this release](https://tickets.puppetlabs.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%202.3.0%22%20ORDER%20BY%20updated%20DESC%2C%20priority%20DESC%2C%20created%20ASC)
