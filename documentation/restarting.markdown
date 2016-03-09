---
layout: default
title: "Puppet Server: Restarting the Server"
canonical: "/puppetserver/latest/restarting.html"
---

[logback.xml]: ./config_file_logbackxml.html
[Hiera]: /hiera/latest/configuring.html
[gems]: /puppetserver/latest/gems.html
[core dependencies]: /puppet/latest/reference/about_agent.html#what-are-puppet-agent-and-puppet-server
[environment]: /puppet/latest/reference/environments.html
[environment caching]: /puppet/latest/reference/configuration.html#environmenttimeout

Puppet Server 2.3.0 and newer support being restarted by sending a hangup signal, also known as [HUP or SIGHUP](https://en.wikipedia.org/wiki/SIGHUP), to the running Puppet Server process. You can send this signal to the Puppet Server process using the standard [`kill`](http://linux.die.net/man/1/kill) command.

For example, this command sends a HUP signal to the process named `puppet-server`:

    kill -HUP `pgrep -f puppet-server`

The HUP signal stops Puppet Server and reloads it gracefully, without terminating the JVM process. This is generally *much* faster than completely stopping and restarting the process. This allows you to quickly load changes to your Puppet Server master, including configuration changes.

## Changes applied after a full Server restart, SIGHUP, or JRuby pool flush

You can make Puppet Server apply the following types of changes by either restarting the Puppet Server process, sending a HUP signal to the process, or sending a request to the [HTTP Admin API to flush the JRuby pool](./admin-api/v1/jruby-pool.html):

* Changes to your `hiera.yaml` file to change your [Hiera][] configuration
* [Installation or removal of gems][gems] for Puppet Server via `puppetserver gem`
* Changes to the Ruby code for Puppet's [core dependencies][], such as Puppet, Facter, and Hiera
* Changes to Puppet modules in an [environment][] where you've enabled [environment
  caching][] (you can also achieve this by hitting the
  [Admin API for flushing the environment cache](./admin-api/v1/environment-cache.html)

## Changes applied after a full Server restart or SIGHUP

### Puppet Server's configuration in `conf.d`

To have Puppet Server apply changes to the [configuration files](./configuration.html) in its `conf.d` directory, you can either restart the process or send it a HUP signal.

> **Note:** Changes to Puppet Server's [logging configuration in `logback.xml`][logback.xml] don't require a server reload or restart. Puppet Server recognizes and applies them automatically, though it can take a minute or so for this to happen.

## Changes that require a full Server restart

### JVM arguments

If you restart Puppet Server by sending it a HUP signal, it doesn't apply changes to JVM command-line arguments (such as the JVM's [heap size settings](./tuning_guide.html#jvm-heap-size)) that are typically configured in your `/etc/sysconfig/puppetserver` file. You must restart the process via the operating system's service framework, for instance by using the `systemctl` or `service` commands.

### `bootstrap.cfg`

If you modify [`bootstrap.cfg`](./configuration.html#service-bootstrapping) to enable or disable Puppet Server's certificate authority (CA) service, you must restart the Puppet Server process via the operating system's service framework.
