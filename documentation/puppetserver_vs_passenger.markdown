---
layout: default
title: "Puppet Server: Puppet Server vs. Apache/Passenger Puppet Master"
canonical: "/puppetserver/latest/puppetserver_vs_passenger.html"
---


Puppet Server is intended to function as a drop-in replacement for the existing
Apache/Passenger Puppet master stack. However, there are a handful of differences with Puppet Server due to changes in the underlying architecture.

This page details things that are intentionally different between the two
applications. You may also be interested in the [Known Issues](./known_issues.markdown)
page, where we've listed a handful of issues that we expect to fix in future releases.

## Service Name

Since the Apache/Passenger master runs under, well, Apache, the name of the service
that you use to start and stop the master is `httpd` (or `apache2`, depending
on your platform). With Puppet Server, the service name is `puppetserver`. So
to start and stop the service, you'll use `puppetserver` commands, such as `service puppetserver restart`.

## Config Files

Puppet Server honors almost all settings in `puppet.conf` and should pick them
up automatically. However, for some tasks, such as configuring the webserver or an external Certificate Authority, we have introduced new Puppet Server-specific configuration files and settings. These new files and settings are detailed on the [Puppet Server Configuration](./configuration.markdown) page.

## Gems

If you have server-side Ruby code in your modules, Puppet Server will run it via
JRuby. Generally speaking, this only affects custom parser functions and report
processors. For the vast majority of cases, this shouldn't pose any problems, as JRuby is highly compatible with vanilla Ruby.

## Installing And Removing Gems

We isolate the Ruby load paths that are accessible to Puppet Server's
JRuby interpreter, so that it doesn't load any gems or other code that
you have installed on your system Ruby. If you want Puppet Server to load additional gems, use the Puppet Server-specific `gem` command to install them. For more details on how Puppet Server interacts with gems, see the [Puppet Server and Gems](./gems.markdown)
page.

## Startup Time

Because Puppet Server runs on the JVM, it takes a bit longer than the Apache/Passenger stack to start and get ready to accept HTTP connections.

Overall, Puppet Server performance is significantly better than a Puppet master running on the Apache/Passenger stack, but the initial startup is definitely slower.

## External CA Configuration

You can configure Puppet Server for use with an external CA instead of the
internal Puppet CA. You'll do this a little differently for Puppet Server than you would for an Apache/Passenger configuration. See the
[External CA Configuration](./external_ca_configuration.markdown) page for
more details.

## External SSL Termination

See [External SSL termination](external_ssl_termination.markdown) for details on
how to get this working in Puppet Server.

## `/status/` endpoint not exposed

The Puppet masters's HTTP API provides a [`/:environment/status/` endpoint](http://docs.puppetlabs.com/references/3.7.latest/developer/file.http_status.html) 
which returns a minimalistic but useful message if the master is alive and well. This endpoint is not exposed on masters running under
Puppet Server. Users wanting a quick health-check URL, for use by load balancers or monitoring systems, can retrieve the CA certificate.
Construct a URL like:  `https://puppet:8140/production/certificate/ca` and check for a non-zero-byte response with a HTTP 200 code.
The work to construct a replacement for the `/status` endpoint is tracked in JIRA at [SERVER-475](https://tickets.puppetlabs.com/browse/SERVER-475).
