---
layout: default
title: "Puppet Server: Restarting the Server"
canonical: "/puppetserver/latest/restarting.html"
---

In Puppet Server 2.3.0, we added support for sending a HUP signal to the running
Puppet Server process.  You can do this via the normal unix `kill` command; you'll
just need to know the PID of the puppet server process.  So, a command like this
should work:

    kill -HUP `pgrep -f puppet-server`

Sending a HUP will cause the server to stop and reload, gracefully, without actually
terminating the JVM process.  This is generally *much* faster than restarting the
entire process.

There are several reasons you might wish to consider restarting / reloading the
server; here we'll go over the most common ones and what your options are for each.

## Change Logging Configuration

Logging configuration changes, made in logback.xml, actually do not require a
server reload or restart; they will be picked up automatically.  It may take
a minute or so for this to happen.

## Changes that require a full server restart

### JVM Arguments

If you need to change JVM command-line arguments (e.g. memory settings such as
-Xms/-Xmx, etc., most likely changed in your `/etc/sysconfig/puppetserver` file),
you'll need to do a full restart of the process via the operating
system's service framework.  e.g. `systemctl restart puppetserver`.

## Changes that will take effect either via restart or HUP

### Change Puppet Server config in conf.d

To pick up any changes to the config files in Puppet Server's conf.d directory,
you can either restart the process or send it a HUP.

## Changes that will take affect after restart, HUP, or JRuby pool flush

For any of the following types of changes, you can make them take effect by
restarting the process, HUP'ing the process, or by making a request to the
[HTTP Admin API to flush the JRuby pool](./admin-api/v1/jruby-pool.html).

* Changes to your hiera.yaml file to change your hiera configuration
* Installation or removal of gems for Puppet Server via `puppetserver gem`
* Changes to the Ruby code for the core dependencies (Puppet, Facter, Hiera)
* Changes to Puppet modules in an environment where you've enabled environment
  caching (you can also achieve this by hitting the
  [Admin API for flushing the environment cache](./admin-api/v1/environment-cache.html)