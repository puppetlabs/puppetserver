# Known Issues

For a list of all known issues, visit our [Issue Tracker](https://tickets.puppetlabs.com/browse/SERVER).

Here are a few specific issues that we're aware of that might affect certain users:

## Ruby 1.8 vs Ruby 1.9

Puppet Server uses an embedded JRuby interpreter to execute Ruby code. This
interpreter is compatible with Ruby 1.9. If you are installing
Puppet Server on an existing system with Ruby 1.8, the behavior of some extensions, such as custom functions and custom resource types and providers, might change slightly. Generally speaking, this shouldn't affect core Puppet Ruby code, which is tested against both versions of Ruby. 

## Config Reload

[SERVER-15](https://tickets.puppetlabs.com/browse/SERVER-15): In the current
builds of Puppet Server, there is no signal handling mechanism
that allows you to request a config reload/service refresh. In order to
clear out the Ruby environments and reload all config, you must restart the
service. This is expensive, and in the future we'd like to support some mechanisms
for reloading rather than restarting.

## Diffie-Helman HTTPS Client Issues

[SERVER-17](https://tickets.puppetlabs.com/browse/SERVER-17): When configuring
Puppet Server to use a report processor that involves HTTPS requests (e.g., to
Foreman), there can be compatibility issues between the JVM HTTPS client and
certain server HTTPS implementations (e.g., very recent versions of Apache mod_ssl).
See the linked ticket for known workarounds.

## Uberjar Leiningen Version Issues

If you try to build an uberjar on your own, you need to use leiningen 2.4.3
or later. Earlier versions of leiningen fail to include some of JRuby's
dependencies in the uberjar, which can cause failures that say
`Puppet::Error: Cannot determine basic system flavour` on startup.

## OpenBSD JRuby Compatibility Issues

[SERVER-14](https://tickets.puppetlabs.com/browse/SERVER-14): While we don't ship
official packages or provide official support for OpenBSD, we would very much
like for users to be able to run Puppet Server on it. Current versions of JRuby
have a bug in their POSIX support on OpenBSD that prevents Puppet Server from
running. We will try to work with the JRuby team to see if they can get a fix
in for this, and upgrade to a newer JRuby when a fix becomes available. It might
also be possible to patch the Puppet Ruby code to work around this issue.