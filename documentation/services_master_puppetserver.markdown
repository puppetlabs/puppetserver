---
layout: default
title: "Puppet's Services: Puppet Server"
canonical: "/puppetserver/latest/services_master_puppetserver.html"
---

[rack]: https://puppet.com/docs/puppet/latest/services_master_rack.html
[external_ca]: ./external_ca_configuration.markdown
[cert_manpage]: https://puppet.com/docs/puppet/latest/man/cert.html
[deprecated]: ./deprecated_features.markdown

Puppet is configured in an agent-master architecture, in which a master node controls configuration information for a fleet of managed agent nodes. Puppet Server performs the role of the master node. Puppet Server is a Ruby and Clojure application that runs on the Java Virtual Machine (JVM) and provides the same services as the classic Puppet master application. It mostly does this by running the existing Puppet master code in several JRuby interpreters, but it replaces some parts of the classic application with new services written in Clojure.

This page describes the generic requirements and run environment for Puppet Server; for practical instructions, see the docs for [installing](./install_from_packages.markdown) and [configuring](./configuration.markdown) it. For details about invoking the `puppet master` command, see [the `puppet master` man page](https://puppet.com/docs/puppet/latest/man/master.html).

## Supported Platforms

Puppet provides Puppet Server packages for Red Hat Enterprise Linux, RHEL-derived distros, Fedora, Debian, and Ubuntu.

If we don't provide a package for your system, you can run Puppet Server from source on any POSIX server with JDK 1.7 or later. See [Running from Source](./dev_running_from_source.markdown) for more details.

Note that Puppet Server is versioned separately from Puppet itself. Puppet Server 1.0 is compatible with Puppet 3.7.3 and later, but will not be compatible with Puppet 4.0; there will be a separate Puppet Server release to coincide with the next major version of Puppet.

## Controlling the Service

The Puppet Server service name is `puppetserver`. To start and stop the service, you'll use the usual commands for your OS, such as `service puppetserver restart`, `service puppetserver status`, etc.

## Puppet Server's Run Environment

Puppet Server consists of several related services that share state and route requests among themselves. These services run inside a single JVM process, using the Trapperkeeper service framework.

From a user's perspective, it mostly acts like a single monolithic service. Most of the architectural complexity is wrapped and hidden; the main exception is the handful of extra config files that manage different internal services.

### Embedded Web Server

Puppet Server uses a Jetty-based web server embedded in the service's JVM process. You don't need to do anything special to configure or enable the web server; it works out of the box. It performs well under production-level loads.

The web server's settings can be modified in [`webserver.conf`](./config_file_webserver.markdown). You might need to edit this file if you're [using an external CA][external_ca] or running Puppet on a non-standard port.

### Puppet Master Service

Puppet Server includes a "master" service. See
[Puppet V3 HTTP API](https://puppet.com/docs/puppet/latest/http_api/http_api_index.html#puppet-v3-http-api)
for more information on the basic APIs.

- For docs on the Puppet Server-specific APIs hosted by the master service, see:

    - [The `environment_classes` endpoint](./puppet-api/v3/environment_classes.markdown)
    - [The `environment_modules` endpoint](./puppet-api/v3/environment_modules.markdown)

### Certificate Authority Service

Puppet Server includes a certificate authority (CA) service that accepts certificate signing requests (CSRs) from nodes, serves certificates and a certificate revocation list (CRL) to nodes, and optionally accepts commands to sign or revoke certificates. (The specific endpoints are `certificate`, `certificate_request`, `certificate_revocation_list`, and `certificate_status`.)

See [CA V1 HTTP API](https://puppet.com/docs/puppet/latest/http_api/http_api_index.html#ca-v1-http-api)
for more information on these APIs.

Signing and revoking certificates over the network is disallowed by default; you can use the [`auth.conf`](./config_file_auth.html) file to let specific certificate owners issue commands.

The CA service uses .pem files in the standard Puppet [`ssldir`](https://puppet.com/docs/puppet/latest/dirs_ssldir.html) to store credentials. You can use the standard `puppetserver ca` command to interact with these credentials, including listing, signing, and revoking certificates.

### Admin API Service

Puppet Server includes an administrative API for triggering maintenance tasks.

Right now, the main administrative task is forcing expiration of all environment caches. This lets you deploy new code to long-timeout environments without having to do a lengthy full restart of the service.

- For API docs, see:
    - [The `environment-cache` endpoint](./admin-api/v1/environment-cache.markdown)
    - [The `jruby-pool` endpoint](./admin-api/v1/jruby-pool.markdown)
- For details about environment caching, see [the Puppet environments documentation](https://puppet.com/docs/puppet/latest/environments_about.html#environments-limitations).

### JRuby Interpreters

Most of Puppet Server's work — compiling catalogs, receiving reports, etc. — is still done by Ruby code. But instead of using the operating system's MRI Ruby runtime, Puppet Server runs Puppet in JRuby, an implementation of the Ruby interpreter that runs on the JVM.

Since we don't use the system Ruby, you can't use the system `gem` command to install Ruby Gems for use by the Puppet master. Instead, Puppet Server includes a separate `puppetserver gem` command for installing any libraries that your Puppet extensions might require. See [the "Using Ruby Gems" page](./gems.markdown) for details.

Additionally, if you need to test or debug code that will be used by Puppet Server, we include `puppetserver ruby` and `puppetserver irb` commands that will execute Ruby code in a JRuby environment.

> **Note:** In Puppet Server 2.7.1, you can set custom arguments to be passed into the Java process for the `puppetserver ruby` command via the new `JAVA_ARGS_CLI` environment variable, either temporarily on the command line or persistently by adding it to the sysconfig/default file (typically located at `/etc/sysconfig/puppetserver` or `/etc/defaults/puppetserver`). The `JAVA_ARGS_CLI` environment variable also controls the arguments used when running the `puppetserver gem` and `puppetserver irb` [subcommands](./subcommands.markdown). See the [Server 2.7.1 release notes](https://docs.puppet.com/puppetserver/2.7/release_notes.html) for details.

To handle parallel requests from agent nodes, Puppet Server maintains several separate JRuby interpreters, all independently running Puppet's application code, and distributes agent requests among them. Today, agent requests are distributed more or less randomly, without regard to their environment; this may change in the future.

You can configure the JRuby interpreters in the `jruby-puppet` section of [the `puppetserver.conf` file.](./config_file_puppetserver.markdown)

#### Tuning Guide

You can maximize Puppet Server's performance by tuning your JRuby configuration. To learn more about tuning your configuration, see our Puppet Server [Tuning Guide](./tuning_guide.markdown).

### User

Puppet Server needs to run as the user `pe-puppet` if you are running Puppet Enterprise, or `puppet` if you are running open source Puppet.

The user is specified in `/etc/sysconfig/pe-puppetserver` for PE, or in `/etc/sysconfig/puppetserver` for open source Puppet. (Puppet Server ignores the `user` and `group` settings from puppet.conf.)

All of the Puppet master's files and directories must be readable and writable by this user.

### Ports

By default, Puppet's HTTPS traffic uses port 8140. The OS and firewall must allow Puppet Server's JVM process to accept incoming connections on this port.

You can change the port in `webserver.conf` if necessary. See the [Configuration](./config_file_webserver.markdown) page for details.

Puppet Server completely ignores the `masterport` setting in the puppet.conf file.

### Logging

All of Puppet Server's logging is routed through the JVM [Logback](http://logback.qos.ch/) library. By default, it logs to `/var/log/puppetlabs/puppetserver/puppetserver.log`. The default log level is 'INFO'. By default, Puppet Server sends nothing to syslog.

All log messages follow the same path, including HTTP traffic, catalog compilation, certificate processing, and all other parts of Puppet Server's work.

Puppet Server also relies on Logback to manage, rotate, and archive Server log files. Logback archives Server logs when they exceed 200MB, and when the total size of all Server logs exceeds 1GB, it automatically deletes the oldest logs.

Logback is heavily configurable; if you need something more specialized than a unified log file, you can probably get it. [See the configuration docs for info on configuring logging.](./configuration.markdown#logging)

Finally, there's a special "daemon" log file used only for errors that happen before logging is set up or which cause the logging system to die. This file can be found at `/var/log/puppetlabs/puppetserver/puppetserver-daemon.log` on platforms using SysV-style init, and in journalctl on platforms using systemd.

### SSL Termination

By default, Puppet Server handles SSL termination automatically.

In network configurations that require external SSL termination (e.g. with a hardware load balancer), you'll need to configure a few other things. See the [External SSL Termination](./external_ssl_termination.markdown) page for details. In summary, you'll need to:

* Configure Puppet Server to use HTTP instead of HTTPS
* Configure Puppet Server to accept SSL information via insecure HTTP headers
* Secure your network so that Puppet Server **cannot** be directly reached by **any** untrusted clients
* Configure your SSL terminating proxy to set the following HTTP headers:
    * `X-Client-Verify` (mandatory)
    * `X-Client-DN` (mandatory for client-verified requests)
    * `X-Client-Cert` (optional; required for [trusted facts](https://puppet.com/docs/puppet/latest/lang_facts_and_builtin_vars.html))

## Configuring Puppet Server

Puppet Server uses a combination of Puppet's usual config files along with its own configuration files, which are located in the `conf.d` directory.

Puppet Server's `conf.d` directory contains:

* `global.conf`: Global configuration settings for Puppet Server.
* `webserver.conf` and `web-routes.conf`: Web server configuration settings.
* `puppetserver.conf`: Settings for Puppet Server itself, including the JRuby interpreter and the administrative API.
* `auth.conf`: Authentication rules for Puppet Server endpoints.
* `master.conf` ([deprecated][]): Settings for the Puppet master functionality of Puppet Server.
* `ca.conf`: Settings for the Certificate Authority service.

For detailed information about Puppet Server settings and the `conf.d` directory, refer to the [Configuration](./configuration.markdown) page.

While Puppet Server can use Puppet's [`auth.conf`](https://puppet.com/docs/puppet/latest/config_file_auth.html) for access control, this method is deprecated in favor of a new authentication system introduced in Puppet Server 2.2 and configured through its own [`auth.conf`](./config_file_auth.markdown) file.

As mentioned above, Puppet Server also uses Puppet's usual config files, including most of the settings in [`puppet.conf`](https://puppet.com/docs/puppet/latest/config_file_main.html). However, Puppet Server treats some `puppet.conf` settings differently, and you should be aware of [these differences](./puppet_conf_setting_diffs.markdown).
