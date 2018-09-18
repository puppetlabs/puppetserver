---
layout: default
title: "Puppet Server: Restarting the Server"
canonical: "/puppetserver/latest/restarting.html"
---

[logback.xml]: ./config_file_logbackxml.markdown
[Hiera]: https://puppet.com/docs/puppet/latest/hiera_intro.html
[gems]: ./gems.markdown
[core dependencies]: https://puppet.com/docs/puppet/latest/about_agent.html#what-are-puppet-agent-and-puppet-server
[environment]: https://puppet.com/docs/puppet/latest/environments_about.html
[environment caching]: https://puppet.com/docs/puppet/latest/configuration.html#environmenttimeout

Starting in version 2.3.0, you can restart Puppet Server by sending a hangup signal, also known as a [HUP signal or SIGHUP](https://en.wikipedia.org/wiki/SIGHUP), to the running Puppet Server process. The HUP signal stops Puppet Server and reloads it gracefully, without terminating the JVM process. This is generally *much* faster than completely stopping and restarting the process. This allows you to quickly load changes to your Puppet Server master, including configuration changes.

There are several ways to send a HUP signal to the Puppet Server process, but the most straightforward is to run the following [`kill`](http://linux.die.net/man/1/kill) command:

    kill -HUP `pgrep -f puppet-server`

Starting in version 2.7.0, you can also reload Puppet Server by running the "reload" action via the operating system's service framework. This is analogous to sending a hangup signal but with the benefit of having the "reload" command pause until the server has been completely reloaded, similar to how the "restart" command pauses until the service process has been fully restarted. Advantages to using the "reload" action as opposed to just sending a HUP signal include:

1.  Unlike with the HUP signal approach, you do not have to determine the process ID of the puppetserver process to be reloaded.

2.  When using the HUP signal with an automated script (or Puppet code), it is possible that any additional commands in the script might behave improperly if performed while the server is still reloading. With the "reload" command, though, the server should be up and using its latest configuration before any subsequent script commands are performed.

3.  Even if the server fails to reload and shuts down --- for example, due to a configuration error --- the `kill -HUP` command might still return a 0 (success) exit code. With the "reload" command, however, any configuration change which causes the server to shut down will produce a non-0 (failure) exit code. The "reload" command, therefore, would allow you to more reliably determine if the server failed to reload properly.

Use the following commands to perform the "reload" action for Puppet Server.

All current OS distributions:

    service puppetserver reload

OS distributions which use sysvinit-style scripts:

    /etc/init.d/puppetserver reload

OS distributions which use systemd service configurations:

    systemctl reload puppetserver

## Restarting Puppet Server to pick up changes

There are three ways to trigger your Puppet Server environment to refresh and pick up changes you've made. A request to the [HTTP Admin API to flush the JRuby pool](./admin-api/v1/jruby-pool.markdown) is the quickest, but picks up only certain types of changes. A HUP signal or service reload is also quick, and applies additional changes. Other changes require a full Puppet Server restart.

> **Note:** Changes to Puppet Server's [logging configuration in `logback.xml`][logback.xml] don't require a server restart. Puppet Server recognizes and applies them automatically, though it can take a minute or so for this to happen. However, you can restart the service to force it to recognize those changes.

### Changes applied after a JRuby pool flush, HUP signal, service reload, or full Server restart

* Changes to your `hiera.yaml` file to change your [Hiera][] configuration.
* [Installation or removal of gems][gems] for Puppet Server by `puppetserver gem`.
* Changes to the Ruby code for Puppet's [core dependencies][], such as Puppet, Facter, and Hiera.
* Changes to Puppet modules in an [environment][] where you've enabled [environment
  caching][]. You can also achieve this by making a request to the
  [Admin API endpoint for flushing the environment cache](./admin-api/v1/environment-cache.markdown).
* Changes to the CA CRL file.  For example, a `puppetserver ca clean`

### Changes applied after a HUP signal, service reload, or full Server restart

* Changes to Puppet Server [configuration files](./configuration.markdown) in its `conf.d` directory.
* Changes to the CA CRL file.  For example, a `puppetserver ca clean`

### Changes that require a full Server restart

* Changes to JVM arguments, such as [heap size settings](./tuning_guide.markdown#jvm-heap-size), that are typically configured in your `/etc/sysconfig/puppetserver` or `/etc/default/puppetserver` file.
* Changes to [`ca.cfg`](./configuration.markdown#service-bootstrapping) to enable or disable Puppet Server's certificate authority (CA) service.

For these types of changes, you must restart the process by using the operating system's service framework, for example, by using the `systemctl` or `service` commands.
