---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

[static catalogs]: https://docs.puppet.com/puppet/4.4/reference/static_catalogs.html
[file resources]: https://docs.puppet.com/puppet/4.4/reference/types/file.html
[Puppet catalogs]: https://docs.puppet.com/puppet/4.4/reference/subsystem_catalog_compilation.html
[environment classes API]: ./puppet-api/v3/environment_classes.markdown
[classes]: https://docs.puppet.com/puppet/latest/reference/lang_classes.html
[resource type API]: https://docs.puppet.com/puppet/latest/reference/http_api/http_resource_type.html

## Puppet Server 2.3.2

Released April 27, 2016.

This is a bug-fix release that also resolves a security issue in endpoint URL decoding.

### **Security fix:** CVE-2016-2785 - Correctly decode endpoint URLs

Previous versions of Puppet Server 2.x did not correctly decode specific character combinations that could potentially allow for a host to access endpoints restricted by [`auth.conf`](./config_file_auth.markdown) rules. Puppet Server 2.3.2 resolves this issue.

* [CVE-2016-2785](https://puppet.com/security/cve/cve-2016-2785)

### Bug fix: Support Ruby master's custom trusted OID mappings

Puppet's Ruby master supports [custom trusted certificate extension object identifier (OID) mappings](https://docs.puppet.com/puppet/latest/reference/config_file_oid_map.html), but previous versions of Puppet Server 2.x did not. This could result in Puppet Server storing trusted extensions with their default numeric OIDs as their keys, instead of the expected custom-mapped short names.

Puppet Server 2.3.2 resolves this issue by supporting the `trusted_oid_mapping_file` and `csr_attributes` settings in [`puppet.conf`](https://docs.puppet.com/puppet/4.4/reference/config_file_main.html), which define paths to YAML files containing the custom mappings and certificate extension request (CSR) attributes, respectively.

For more information about mapping custom trusted OID names, see the Puppet documentation for the  [`custom_trusted_oid_mapping.yaml` file](https://docs.puppet.com/puppet/latest/reference/config_file_oid_map.html).

* [SERVER-1150](https://tickets.puppet.com/browse/SERVER-1150)

### All changes

* [All Puppet Server issues targeted at this release](https://tickets.puppet.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%202.3.2%22%20ORDER%20BY%20updated%20DESC%2C%20priority%20DESC%2C%20created%20ASC)

## Puppet Server 2.3.1

Released March 21, 2016.

This is a bug-fix release that resolves a disruptive logging configuration issue.

### Bug fix: Puppet Server starts when configured to log to syslog

If its Logback service is configured to log to syslog, Puppet Server 2.3.0 fails to start. Puppet Server 2.3.1 fixes this regression, which did not affect prior versions of Puppet Server.

* [SERVER-1215](https://tickets.puppet.com/browse/SERVER-1215)

### All changes

* [All Puppet Server issues targeted at this release](https://tickets.puppet.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%202.3.1%22%20ORDER%20BY%20updated%20DESC%2C%20priority%20DESC%2C%20created%20ASC)

## Puppet Server 2.3.0

Released March 16, 2016.

This is a feature release that adds functionality for static catalogs, a new environment classes API, and restarting Puppet Server with a HUP signal.

> ### New requirements
>
> Puppet masters running Puppet Server 2.3 depend on [Puppet Agent 1.4.0](https://docs.puppet.com/puppet/4.4/reference/about_agent.html) or newer, which installs [Puppet 4.4](https://docs.puppet.com/puppet/4.4/) and compatible versions of its related tools and dependencies on the server. Puppet agents running older versions of Puppet Agent can connect to Puppet Server 2.3 --- this requirement applies to the Puppet Agent running on the Puppet Server node *only*.

### New feature: Static catalogs

Puppet Server 2.3.0 and Puppet 4.4.0 implement [static catalogs][], which inline metadata for [file resources][] into [Puppet catalogs][]. This improves the predictability of Puppet runs in workflows that use cached catalogs and file resources fetched from modules on a Puppet master.

* [SERVER-999](https://tickets.puppet.com/browse/SERVER-999)

### New feature: `environment_classes` API

The [environment classes API][] in Puppet Server 2.3.0 serves as a replacement for the Puppet [resource type API][] when requesting information about [classes][] available to a Puppet Server.

* [SERVER-1110](https://tickets.puppet.com/browse/SERVER-1110)

### New feature: Faster service restarts with HUP signals

Puppet Server 2.3.0 and newer support being restarted by sending a hangup signal, also known as [HUP or SIGHUP](./restarting.markdown), to the running Puppet Server process. You can send this signal to the Puppet Server process using the standard `kill` command. The HUP signal stops Puppet Server and reloads it gracefully, without terminating the JVM process. This is generally much faster than completely stopping and restarting the process, and allows you to quickly load changes to your Puppet Server master, including certain configuration changes.

* [SERVER-96](https://tickets.puppet.com/browse/SERVER-96)

### Bug fix: Puppet Server correctly parses complex script arguments

In versions 1.x and 2.2.x, Puppet Server would incorrectly parse commands executed by Puppet code that had complex string interpolation. For example, calls to the `generate()` function --- such as `generate('/bin/sh', '-c', "/usr/bin/python -c 'print \"foo\"'")` --- would spawn a Python REPL and consume a JRuby instance without returning anything. Puppet Server 2.3.0 fixes this issue.

* [SERVER-1160](https://tickets.puppet.com/browse/SERVER-1160)

### Known issues

#### Modifications to `bootstrap.cfg` might cause problems during upgrades to 2.3.0

If you modified `bootstrap.cfg` (for instance, to [enable or disable the Certificate Authority service](./configuration.markdown#service-bootstrapping)), upgrading to Puppet Server 2.3.0 from earlier versions of Puppet Server might fail. For instance, on Red Hat-family distributions of Linux, you might see a warning during the package update:

```
2016-03-09 11:48:17,460 ERROR [main] [p.t.internal] Error during app buildup!
java.lang.RuntimeException: Service ':VersionedCodeService' not found
```

To resolve this, add this line to your `bootstrap.cfg`:

```
puppetlabs.services.versioned-code-service.versioned-code-service/versioned-code-service
```

Alternatively, you can merge your changes into the new version of `bootstrap.cfg` (the `bootstrap.cfg.rpmnew` file in the above example) and replace `bootstrap.cfg` with the new file.

* [SERVER-1058](https://tickets.puppet.com/browse/SERVER-1058)

#### Puppet Server fails to start when configured to log to syslog

If its Logback service is configured to log to syslog, Puppet Server 2.3.0 fails to start. Puppet Server 2.3.1 fixes this regression, which did not affect prior versions of Puppet Server.

* [SERVER-1215](https://tickets.puppet.com/browse/SERVER-1215)

### All changes

* [All Puppet Server issues targeted at this release](https://tickets.puppet.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%202.3.0%22%20ORDER%20BY%20updated%20DESC%2C%20priority%20DESC%2C%20created%20ASC)
