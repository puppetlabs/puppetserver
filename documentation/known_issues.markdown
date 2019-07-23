---
layout: default
title: "Puppet Server: Known Issues"
canonical: "/puppetserver/latest/known_issues.html"
---


For a list of all known issues, visit our [Issue Tracker](https://tickets.puppet.com/browse/SERVER).

## Cipher updates in Puppet Server 6.5

Puppet Server 6.5 includes an upgrade to the latest release of Jetty's 9.4 series. With this update, you may see "weak cipher" warnings about ciphers that were previously enabled by default. Puppet Server now defaults to stronger FIPS-compliant ciphers, but you must first remove the weak ciphers.

The ciphers previously enabled by default have not been changed, but are considered weak by the updated standards. Remove the weak ciphers by removing the `cipher-suite` configuration section from the `webserver.conf`. After you remove the `cipher-suite`, Puppet Server uses the FIPS-compliant ciphers instead. This release includes the weak ciphers for backward compatibility only.

The FIPS-compliant cipher suites, which are not considered weak, will be the default in a future version of Puppet. To maintain backwards compatibility, Puppet Server explicitly enables all cipher suites that were available as of Puppet Server 6.0. When you upgrade to Puppet Server 6.5.0, this affects you in in two ways:
1. The 6.5 package updates the `webserver.conf` file in Puppet Server's `conf.d` directory.
2. When Puppet Server starts or reloads, Jetty warns about weak cipher suites being enabled.

This update also removes the `so-linger-seconds` configuration setting. This setting is now ignored and a warning is issued if it is set. See Jetty's [so-linger-seconds](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/3.0.1/doc/jetty-config.md#so-linger-seconds) for removal details.

> Note: On some older operating systems, you might see additional warnings that newer cipher suites are unavailable. In this case, manage the contents of the `webserver.cipher-suites` configuration value to be those strong suites that available to you.

## Server-side Ruby gems might need to be updated for upgrading from JRuby 1.7

When upgrading from Puppet Server 5 using JRuby 1.7 (9k was optional in those releases), Server-side gems that were installed manually with the `puppetserver gem` command or using the `puppetserver_gem` package provider might need to be updated to work with the newer JRuby. In most cases gems do not have APIs that break when upgrading from the Ruby versions implemented between JRuby 1.7 and JRuby 9k, so there might be no necessary updates. However, two notable exceptions are that the autosign gem should be 0.1.3 or later and yard-doc must be 0.9 or later. 

## Potential JAVA ARGS settings

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

When Puppet Server is installed from packages, add this property
to the `JAVA_ARGS` and `JAVA_ARGS_CLI` variables defined in either
`/etc/sysconfig/puppetserver` or `/etc/default/puppetserver`, depending on
your distribution. Invocations of the `gem`, `ruby`, and `irb` subcommands
use the updated `JAVA_ARGS_CLI` on their next invocation. The service will
need to be restarted in order to re-read the `JAVA_ARGS` variable.

## Puppet Server Master Fails to Connect to Load-Balanced Servers with Different SSL Certificates

[SERVER-207](https://tickets.puppet.com/browse/SERVER-207): Intermittent
SSL connection failures have been seen when the Puppet Server master tries to
make SSL requests to servers via the same virtual ip address.  This has been
seen when the servers present different certificates during the SSL handshake.
For more information on the issue, see
[this page](./ssl_server_certificate_change_and_virtual_ips.markdown).
