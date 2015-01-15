---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

## Puppet Server 1.0.2

The 1.0.2 release of Puppet Server includes several bug fixes. It also improves logging functionality by allowing Logback changes to take effect without a restart.

### Bug Fixes

#### Filebucket files treated as binary data

Puppet Server now treats filebucket files as binary data. This prevents possible data alteration resulting from Puppet Server inappropriately treating all filebucket files as text data.

* [SERVER-269](https://tickets.puppetlabs.com/browse/SERVER-269): Puppet Server aggressively coerces request data to UTF-8

#### `puppetserver gem env` command now works

This release fixes functionality of the `puppetserver gem env` command. Previously, this command was throwing an error because the entire system environment was being cleared. 

* [SERVER-262](https://tickets.puppetlabs.com/browse/SERVER-262): `puppetserver gem env` does not work, useful for troubleshooting

#### Startup time extended for systemd 

In 1.0.0, we extended the allowed startup time from 60 to 120 seconds, but we missed the systemd configuration. Now both the init script and systemd configs have the same timeout. 

* [SERVER-166](https://tickets.puppetlabs.com/browse/SERVER-166): Set START_TIMEOUT to 120 seconds for sysv init scripts and systemd.

### Improvements

Puppet Server now picks up changes to logging levels at runtime, rather than requiring a system restart to detect Logback changes.

* [SERVER-275](https://tickets.puppetlabs.com/browse/SERVER-275): Fixed an issue where logback levels weren't changed unless you restarted Puppet Server. 


## Puppet Server 1.0.0

This release is the official "one point oh" version of Puppet Server. In
accordance with the [Semantic Versioning](http://semver.org) specification,
we're declaring the existing public API of this version to be the
baseline for backwards-incompatible changes, which will trigger another
major version number. (No backwards-incompatible changes were introduced
between 0.4.0 and this version.)

In addition, this release adds HTTP endpoints to refresh data and CLI tools for working with the JRuby runtime.

### Compatibility Note

Puppet Server 1.x works with Puppet 3.7.3 and all subsequent Puppet 3.x versions. (When Puppet 4 is released, we’ll release a new Puppet Server version to support it.)

### New Feature: Admin API for Refreshing Environments

This release adds two new HTTP endpoints to speed up deployment of Puppet code changes. Previously, such changes might require a restart of the entire Puppet Server instance, which can be rather slow. These new endpoints allow you to refresh the environment without restarting it.

If you need this feature, you should probably use the `environment-cache` endpoint, since it’s faster than the `jruby-pool` endpoint. To use it, you’ll need to get a valid certificate from Puppet’s CA, add that certificate’s name to the `puppet-admin -> client-whitelist` setting in `puppetserver.conf`, and use that certificate to do an HTTP DELETE request at the `environment-cache` endpoint. For more details, see [the API docs for `environment-cache`.](./admin-api/v1/environment-cache.markdown)

* [SERVER-150](https://tickets.puppetlabs.com/browse/SERVER-150): Add functionality to JRuby service to trash instance.
* [SERVER-151](https://tickets.puppetlabs.com/browse/SERVER-151): Add an HTTP endpoint to call flush jruby pool function.
* [SERVER-112](https://tickets.puppetlabs.com/browse/SERVER-112): Create environment cache entry factory implementation that allows flushing all environments.
* [SERVER-114](https://tickets.puppetlabs.com/browse/SERVER-114): Add `flush_environment_cache` admin endpoint.

### New Feature: `puppetserver ruby` and `puppetserver irb` Commands

This release adds two new CLI commands: `puppetserver ruby` and `puppetserver irb`. These work like the normal `ruby` and `irb` commands, except they use Puppet Server’s JRuby environment instead of your operating system’s version of Ruby. This makes it easier to develop and test Ruby code for use with Puppet Server.

* [SERVER-204](https://tickets.puppetlabs.com/browse/SERVER-204): `puppetserver ruby` cli tool.
* [SERVER-222](https://tickets.puppetlabs.com/browse/SERVER-222): Add `puppetserver irb` cli command.

### New Feature: `puppetserver foreground` Command

The new `puppetserver foreground` command will start an instance of Puppet Server in the foreground, which will log directly to the console with higher-than-normal detail.

This behavior is similar to the traditional `puppet master --verbose --no-daemonize` command, and it’s useful for developing extensions, tracking down problems, and other tasks that are a little outside the day-to-day work of running Puppet.

* [SERVER-141](https://tickets.puppetlabs.com/browse/SERVER-141): Add `foreground` subcommand.

### General Bug Fixes

The `service puppetserver start` and `restart` commands will now block until Puppet Server is actually started and ready to work. (Previously, the init script would return with success before Puppet Server was actually online.) This release also fixes bugs that could cause startup to hang or to timeout prematurely, and a subtle settings bug.

* [SERVER-205](https://tickets.puppetlabs.com/browse/SERVER-205): `wait_for_app` functions occasionally fails to read pidfile on debian and hangs indefinitely.
* [SERVER-166](https://tickets.puppetlabs.com/browse/SERVER-166): Set `START_TIMEOUT` to 120 seconds for sysv init scripts and systemd.
* [SERVER-221](https://tickets.puppetlabs.com/browse/SERVER-221): Run mode not initialized properly

### Performance Improvements

This release improves performance of the certificate status check. Previously, the CRL file was converted to an object once per CSR and signed certificate; as of this release, the object will be reused across checks instead of created for every check.

* [SERVER-137](https://tickets.puppetlabs.com/browse/SERVER-137): Compose X509CRL once and reuse for get-certificate-statuses.

### All Changes

For a list of all changes in this release, see the following Jira pages:

* [All Puppet Server issues targeted at this release](https://tickets.puppetlabs.com/browse/SERVER/fixforversion/12023/)
* [All Trapperkeeper issues targeted at this release](https://tickets.puppetlabs.com/browse/TK/fixforversion/12131/)

## Puppet Server 0.4.0
This release contains improvements based on feedback from the community and
Puppet Labs QA testing. It has usability and correctness improvements, mainly
around SSL and our interaction with systemd. Notable changes:

* (SERVER-89) The Puppet Server CA now creates a 'puppet' Subject Alternate
  Name for master certificates for closer compatibility with the Ruby CA.
* (SERVER-86) The CA no longer uses the 'ca_pub.pem' (which isn't guaranteed
  to exist) when signing or revoking; instead it extracts the key from the
  certificate directly (which IS guaranteed to be there).
* (SERVER-70, SERVER-8, SERVER-84) Improvements around packaging will make
  the Puppet Server behave better under OSes which use systemd and will now
  preserve local changes to the /etc/sysconfig/puppetserver config on
  upgrade.

For a full list of bugs fixed in this release, check out the JIRA release page:
https://tickets.puppetlabs.com/browse/SERVER/fixforversion/12014

## Puppet Server 0.3.0
This is the first feature update since the initial Puppet Server release.
Notable user-facing improvements are:

* (SERVER-18, SERVER-39) Puppet Server now supports externally-terminated SSL
  in the same way as external termination on Apache+Passenger does.
* (SERVER-4) Improve error messages and user feedback when starting on systems
  with low memory. (We recommend at least 2GB RAM)
* (SERVER-43) Add support for HTTP "basic" authentication; this was preventing
  the 'http' report processor used by Dashboard from working.

For a full list of bugs fixed in the release, check out this JIRA page:
https://tickets.puppetlabs.com/browse/SERVER/fixforversion/11955

## Puppet Server 0.2.2
* (SERVER-13) Fix for file descriptor leak during report processing
* (SERVER-7) Add licensing and copyright info
* HTTP client connections from the master use the `localcacert` puppet.conf
  setting to find the CA certs to use for validating a server.  Previously, the
  `cacert` puppet.conf setting was used to find the CA certs used to validate
  the server.

## Puppet Server 0.2.1
* (SERVER-9) Privileged data is accessible to non-privileged local users [CVE-2014-7170]

## Puppet Server 0.2.0
 Initial Open Source Release
