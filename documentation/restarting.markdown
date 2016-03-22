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

Starting in version 2.3.0, you can restart Puppet Server by sending a hangup signal, also known as a [HUP signal or SIGHUP](https://en.wikipedia.org/wiki/SIGHUP), to the running Puppet Server process. The HUP signal stops Puppet Server and reloads it gracefully, without terminating the JVM process. This is generally *much* faster than completely stopping and restarting the process. This allows you to quickly load changes to your Puppet Server master, including configuration changes.

There are several ways to send a HUP signal to the Puppet Server process, but the most straightforward is to run the following [`kill`](http://linux.die.net/man/1/kill) command:

    kill -HUP `pgrep -f puppet-server`

## Restarting Puppet Server to pick up changes

There are three ways to trigger your Puppet Server environment to refresh and pick up changes you've made. A request to the [HTTP Admin API to flush the JRuby pool](./admin-api/v1/jruby-pool.html) is the quickest, but picks up only certain types of changes. A HUP signal restart is also quick, and applies additional changes. Other changes require a full Puppet Server restart.

> **Note:** Changes to Puppet Server's [logging configuration in `logback.xml`][logback.xml] don't require a server reload or restart. Puppet Server recognizes and applies them automatically, though it can take a minute or so for this to happen.

### Changes applied after a JRuby pool flush, HUP signal, or full Server restart

* Changes to your `hiera.yaml` file to change your [Hiera][] configuration.
* [Installation or removal of gems][gems] for Puppet Server by `puppetserver gem`.
* Changes to the Ruby code for Puppet's [core dependencies][], such as Puppet, Facter, and Hiera.
* Changes to Puppet modules in an [environment][] where you've enabled [environment
  caching][]. You can also achieve this by making a request to the
  [Admin API endpoint for flushing the environment cache](./admin-api/v1/environment-cache.html).

### Changes applied after a HUP signal or full Server restart

* Changes to Puppet Server [configuration files](./configuration.html) in its `conf.d` directory.

### Changes that require a full Server restart

* Changes to JVM arguments, such as [heap size settings](./tuning_guide.html#jvm-heap-size), that are typically configured in your `/etc/sysconfig/puppetserver` file.
* Changes to [`bootstrap.cfg`](./configuration.html#service-bootstrapping) to enable or disable Puppet Server's certificate authority (CA) service.

For these types of changes, you must restart the process by using the operating system's service framework, for example, by using the `systemctl` or `service` commands.