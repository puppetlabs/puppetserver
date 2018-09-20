---
layout: default
title: "Puppet Server: Known Issues"
canonical: "/puppetserver/latest/known_issues.html"
---


For a list of all known issues, visit our [Issue Tracker](https://tickets.puppet.com/browse/SERVER).

Here are a few specific issues that we're aware of that might affect certain users:

## Server-side Ruby gems might need to be updated for upgrading with JRuby 1.7

When upgrading from Puppet Server 5 using JRuby 1.7 (9k was optional in those releases), Server-side gems that were installed manually with the `puppetserver gem` command or using the `puppetserver_gem` package provider might need to be updated to work with the newer JRuby. In most cases gems do not have APIs that break when upgrading from the Ruby versions implemented between JRuby 1.7 and JRuby 9k, so there might be no necessary updates. However, two notable exceptions are that the autosign gem should be 0.1.3 or later and yard-doc must be 0.9 or later. 

If you're working outside of lab environment, increase `ReservedCodeCache` to `512m` under normal load. If you're working with 6-12 JRuby instances (or a `max-requests-per-instance` value significantly less than 100k), run with a `ReservedCodeCache` of 1G. Twelve or more JRuby instances in a single server might require 2G or more. 

Similar caveats regarding scaling `ReservedCodeCache` might apply if users are managing `MaxMetaspace`.

## `tmp` directory mounted `noexec`

In some cases (especially for RHEL 7 installations) if the `/tmp` directory is
mounted as `noexec`, Puppet Server may fail to run correctly, and you may see an
error in the Puppet Server logs similar to the following:

```
Nov 12 17:46:12 fqdn.com java[56495]: Failed to load feature test for posix: can't find user for 0
Nov 12 17:46:12 fqdn.com java[56495]: Cannot run on Microsoft Windows without the win32-process, win32-dir and win32-service gems: Win32API only supported on win32
Nov 12 17:46:12 fqdn.com java[56495]: Puppet::Error: Cannot determine basic system flavour
```

This is caused by the fact that JRuby contains some embedded files which need to be
copied somewhere on the filesystem before they can be executed
([see this JRuby issue](https://github.com/jruby/jruby/issues/2186)). To work
around this  issue, you can either mount the `/tmp` directory without
`noexec`, or you can choose a different directory to use as the temporary
directory for the Puppet Server process.

Either way, you'll need to set the permissions of the directory to `1777`. This allows the Puppet Server JRuby process to write a file to `/tmp` and then execute it. If permissions are set incorrectly, you'll get a massive stack trace without much useful information in it.

To use a different temporary directory, you can set the following JVM property:

```
-Djava.io.tmpdir=/some/other/temporary/directory
```

When Puppet Server is installed from packages, this property should be added
to the `JAVA_ARGS` variable defined in either `/etc/sysconfig/puppetserver`
or `/etc/default/puppetserver`, depending on upon your distribution. Note that
the service will need to be restarted in order for this change to take effect.


## Diffie-Helman HTTPS Client Issues

[SERVER-17](https://tickets.puppet.com/browse/SERVER-17): When configuring
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

[SERVER-14](https://tickets.puppet.com/browse/SERVER-14): While we don't ship
official packages or provide official support for OpenBSD, we would very much
like for users to be able to run Puppet Server on it. Current versions of JRuby
have a bug in their POSIX support on OpenBSD that prevents Puppet Server from
running. We will try to work with the JRuby team to see if they can get a fix
in for this, and upgrade to a newer JRuby when a fix becomes available. It might
also be possible to patch the Puppet Ruby code to work around this issue.

## Puppet Server Master Fails to Connect to Load-Balanced Servers with Different SSL Certificates

[SERVER-207](https://tickets.puppet.com/browse/SERVER-207): Intermittent
SSL connection failures have been seen when the Puppet Server master tries to
make SSL requests to servers via the same virtual ip address.  This has been
seen when the servers present different certificates during the SSL handshake.
For more information on the issue, see
[this page](./ssl_server_certificate_change_and_virtual_ips.markdown).
