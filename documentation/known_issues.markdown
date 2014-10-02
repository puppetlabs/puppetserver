Known Issues
=====

For a list of all known issues, visit our [Issue Tracker]()

Here are a few specific issues that we're aware of that we are particularly
interested in addressing in future releases:

SSL Termination
-----

[SERVER-18](https://tickets.puppetlabs.com/browse/SERVER-18): It's been brought
to our attention that many users have configured their environments to handle
SSL termination on a hardware load balancer.  In the Apache/Passenger Puppet
Master stack, this situation was handled by supporting some custom HTTP headers
where the client certificate information could be stored when the SSL was
terminated, thus making it possible for Puppet to continue to perform authorization
checks based on the client certificate data even when communicating via HTTP
instead of HTTPS.  We do not yet support this, but intend to support it very soon.


Config Reload
-----

[SERVER-15](https://tickets.puppetlabs.com/browse/SERVER-15): In the current
builds of Puppet Server, there is no signal handling mechanism
that allows you to request a config reload/service refresh.  In order to
clear out the Ruby environments and reload all config, you must restart the
service.  This is expensive, and in the future we'd like to support some mechanisms
for reloading rather than restarting.

Diffie-Helman HTTPS Client Issues
-----

[SERVER-17](https://tickets.puppetlabs.com/browse/SERVER-17): When configuring
Puppet Server to use a report processor that involves HTTPS requests (e.g. to
Foreman), there can be compatibility issues between the JVM HTTPS client and
certain server HTTPS implementations (e.g. very recent versions of Apache mod_ssl).
See the linked ticket for known workarounds.

OpenBSD JRuby Compatibility Issues
-----

[SERVER-14](https://tickets.puppetlabs.com/browse/SERVER-14): While we do not ship
official packages or provide official support for OpenBSD, we would very much
like for users to be able to run Puppet Server on it.  Current versions of JRuby
have a bug in their POSIX support on OpenBSD, which prevent Puppet Server from
running.  We will try to work with the JRuby team to see if they can get a fix
in for this, and upgrade to a newer JRuby when a fix becomes available.  It might
also be possible to patch the Puppet Ruby code to work around this issue.