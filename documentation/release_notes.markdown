---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

[Trapperkeeper]: https://github.com/puppetlabs/trapperkeeper
[service bootstrapping]: ./configuration.markdown#service-bootstrapping
[auth.conf]: ./config_file_auth.markdown

For release notes on versions of Puppet Server prior to Puppet Server 2.5, see [docs.puppet.com](https://docs.puppet.com/puppetserver/2.4/release_notes.html).

## Puppet Server 2.6

Released September 8, 2016.

This is a feature and bug-fix release of Puppet Server. This release also adds an official Puppet Server package for SuSE Enterprise Linux (SLES) 12.

> **Warning:** If you're upgrading from Puppet Server 2.4 or earlier and have modified `bootstrap.cfg`, `/etc/sysconfig/puppetserver`, or `/etc/default/puppetserver`, see the [Puppet Server 2.5 release notes first](#potential-breaking-issues-when-upgrading-with-a-modified-bootstrapcfg) **before upgrading** for instructions on avoiding potential failures.

### New feature: JVM metrics endpoint `/status/v1/services`

Puppet Server provides a new endpoint, `/status/v1/services`, which can provide basic Java Virtual Machine-level metrics related to the current Puppet Server process's memory usage.

To request this data, make an HTTP GET request to Puppet Server with a query string of `level=debug`. For details on the endpoint and its response, see the [Services endpoint documentation](./status-api/v1/services.markdown).

> **Experimental feature note:** These metrics are experimental. The names and values of the metrics may change in future releases.

- [SERVER-1502](https://tickets.puppetlabs.com/browse/SERVER-1502)

### New feature: Logback replaces logrotate for Server log rotation

Previous versions of Puppet Server would rotate and compress logs daily using logrotate. Puppet Server 2.6 uses Logback, the logging library used by Puppet Server's Java Virtual Machine (JVM).

Under logrotate, certain pathological error states --- such as running out of file handles --- could cause previous versions of Puppet Server to fill up disk partitions with logs of stack traces.

In Puppet Server 2.6, Logback compresses Server-related logs into archives when their size exceeds 10MB. Also, when the total size of all Puppet Server logs exceeds 1GB, Logback deletes the oldest logs. These improvements should limit the space that Puppet Server's logs consume and prevent them from filling partitions.

> **Debian upgrade note:** On Debian-based Linux distributions, logrotate will continue to attempt to manage your Puppet Server log files until `/etc/logrotate.d/puppetserver` is removed. These logrotate attempts are harmless, but will generate a duplicate archive of logs. As a best practice, delete `puppetserver` from `logrotate.d` after upgrading to Puppet Server 2.6.
>
> This doesn't affect clean installations of Puppet Server on Debian, or any upgrade or clean installation on other Linux distributions.

- [SERVER-366](https://tickets.puppetlabs.com/browse/SERVER-366)

### Bug fixes: Update JRuby to resolve several issues

This release resolves two issues by updating the version of JRuby used by Puppet Server to 1.7.26.

In previous versions of Puppet Server 2.x, when a variable lookup is performed from Ruby code or an ERB template and the variable is not defined, catalog compilation could periodically fail with an error message similar to:

```
Puppet Evaluation Error: Error while evaluating a Resource Statement, Evaluation Error: Error while evaluating a Function Call, integer 2181729414 too big to convert to `int` at <PUPPET FILE>
```

The error message is inaccurate; the lookup should return nil. The error is a [bug in JRuby](https://github.com/jruby/jruby/issues/3980), which Puppet Server uses to run Ruby code. Puppet Server 2.6 resolves this by updating JRuby.

-   [SERVER-1408](https://tickets.puppetlabs.com/browse/SERVER-1408)

Also, when Puppet Server uses a large JVM memory heap and large number of JRuby instances, Puppet Server could fail to start and produce error messages in the `puppetserver.log` file similar to:

```
java.lang.IllegalStateException: There was a problem adding a JRubyPuppet instance to the pool.
Caused by: org.jruby.embed.EvalFailedException: (LoadError) load error: jopenssl/load -- java.lang.NoClassDefFoundError: org/jruby/ext/openssl/NetscapeSPKI
```

We [fixed the underlying issue in JRuby](https://github.com/jruby/jruby/pull/4063), and this fix is included in Puppet Server 2.6.

- [SERVER-858](https://tickets.puppetlabs.com/browse/SERVER-1408)

### New feature: Whitelist Ruby environment variables

Puppet Server 2.6 adds the ability to specify a whitelist of environment variables made available to Ruby code. To whitelist variables, add them to the `environment-vars` section under the `jruby-puppet` configuration section in [`puppetserver.conf`](./config_file_puppetserver.markdown).

- [SERVER-584](https://tickets.puppetlabs.com/browse/SERVER-584)

## Puppet Server 2.5

Released August 11, 2016.

This is a feature and bug-fix release of Puppet Server.

> ### Potential breaking issues when upgrading with a modified `bootstrap.cfg`
>
> If you disabled the certificate authority (CA) on Puppet Server by editing the [`bootstrap.cfg`][service bootstrapping] file on older versions of Puppet Server --- for instance, because you have a multi-master configuration with the default CA disabled on some masters, or use an external CA --- be aware that Puppet Server as of version 2.5.0 no longer uses the `bootstrap.cfg` file.
>
> Puppet Server 2.5.0 and newer instead create a new configuration file, `/etc/puppetlabs/puppetserver/services.d/ca.cfg`, if it doesn't already exist, and this new file enables CA services by default.
>
> To ensure that CA services remain disabled after upgrading, create the `/etc/puppetlabs/puppetserver/services.d/ca.cfg` file with contents that disable the CA services _before_ you upgrade to Server 2.5.0 or newer. The `puppetserver` service restarts after the upgrade if the service is running before the upgrade, and the service restart also reloads the new `ca.cfg` file.
>
> Also, back up your masters' [`ssldir`](https://docs.puppet.com/puppet/latest/reference/dirs_ssldir.html) (or at least your `crl.pem` file) _before_ you upgrade to ensure that you can restore your previous certificates and certificate revocation list, so you can restore them in case any mistakes or failures to disable the CA services in `ca.cfg` lead to a master unexpectedly enabling CA services and overwriting them.
>
> For more details, including a sample `ca.cfg` file that disables CA services, see the [bootstrap upgrade notes](./bootstrap_upgrade_notes.markdown).

> ### Potential service failures when upgrading with a modified init configuration
>
> If you modified the init configuration file --- for instance, to [configure Puppet Server's JVM memory allocation](./install_from_packages.html#memory-allocation) or [maximum heap size](./tuning_guide.html) --- and upgrade Puppet Server 2.5.0 or newer with a package manager, you might see a warning during the upgrade that the updated package will overwrite the file (`/etc/sysconfig/puppetserver` in Red Hat and derivatives, or `/etc/default/puppetserver` in Debian-based systems).
>
> The changes to the file support the new service bootstrapping behaviors. If you don't accept changes to the file during the upgrade, the puppetserver service fails and you might see a `Service ':PoolManagerService' not found` or similar warning. To resolve the issue, set the `BOOTSTRAP_CONFIG` setting in the init configuration file to:
>
>     BOOTSTRAP_CONFIG="/etc/puppetlabs/puppetserver/services.d/,/opt/puppetlabs/server/apps/puppetserver/config/services.d/"
>
> If you modified other settings in the file before upgrading, and then overwrite the file during the upgrade, you might need to reapply those modifications after the upgrade.

### New feature: Flexible service bootstrapping/CA configuration file

To disable the Puppet CA service in previous versions of Puppet Server 2.x, users edited the [`bootstrap.cfg`][service bootstrapping] file, usually located at `/etc/puppetlabs/puppetserver/bootstrap.cfg`.

This workflow could cause problems for users performing package upgrades of Puppet Server where `bootstrap.cfg` was modified, because the package might overwrite the modified `bootstrap.cfg` and undo their changes.

To improve the upgrade experience for these users, Puppet Server 2.5.0 can load the service bootstrapping settings from multiple files. This in turn allows us to provide user-modifiable settings in a separate file and avoid overwriting any changes during an upgrade.

-   [SERVER-1470](https://tickets.puppetlabs.com/browse/SERVER-1470)
-   [SERVER-1247](https://tickets.puppetlabs.com/browse/SERVER-1247)
-   [SERVER-1213](https://tickets.puppetlabs.com/browse/SERVER-1213)

### New feature: Signing CSRs with OIDs from a new arc

Puppet Server 2.5.0 can sign certificate signing requests (CSRs) from Puppet 4.6 agents that contain a new custom object identifier (OID) arc to represent secured extensions for use with [`trapperkeeper-authorization`][Trapperkeeper].

> **Aside:** Trapperkeeper powers the [HOCON `auth.conf` and authorization methods][auth.conf] introduced in Puppet Server 2.2.0. This new CSR-signing functionality in Server 2.5.0 builds on features added to Puppet 4.6 and the addition of X.509 extension-based authorization rules added to Trapperkeeper alongside Puppet Server 2.4.

To sign CSRs wth the new OID arc via the Puppet 4.6 command-line tools, use the `puppet cert sign --allow-authorization-extensions` command. See the [`puppet cert` man page](https://docs.puppet.com/puppet/4.6/reference/man/cert.html) for details. This workflow is similar to signing DNS alt names.

The new OID arc is "puppetlabs.1.3", with a long name of "Puppet Authorization Certificate Extension" and short name of `ppAuthCertExt` (where "puppetlabs" is our registered OID arc 1.3.6.1.4.1.34380). Set the extension "puppetlabs.1.3.1" (`pp_authorization`) on CSRs that need to be authenticated via the new workflow. We've also included an default alias of `pp_auth_role` at extension "puppetlabs.1.3.13" for common workflows. See [the Puppet CSR attributes and certificate extensions documentation](https://docs.puppet.com/puppet/4.6/reference/ssl_attributes_extensions.html) for more information.

We've also improved the CLI output of `puppet cert list` and `puppet cert sign` to work better with the `--human-readable` and `--machine-readable` flags, and we allow administrators to force a prompt when signing certificates with the `--interactive` flag.

This allows for easier automated failover to authorized nodes within a Puppet infrastructure and provides tools for creating new, securely automated workflows, such as automated component promotions within Puppet-managed infrastructure.

-   [SERVER-1305](https://tickets.puppetlabs.com/browse/SERVER-1305)

### Bug fix: Unrecognized parse-opts

Puppet Server 2.4.x used a deprecated API for a Clojure CLI option-parsing library. As a result, calls to `puppetserver gem` (either directly, or indirectly by using a `puppetserver_gem` package resource) generated unexpected warning messages:

    Warning: Could not match Warning: The following options to parse-opts are unrecognized: :flag

Puppet Server 2.5.0 updates this library, which prevents this error message from appearing.

-   [SERVER-1378](https://tickets.puppetlabs.com/browse/SERVER-1378)

### Bug fix: Puppet Server no longer ships with an empty PID file

When installed on CentOS 6, Puppet Server 2.4.x included an empty PID file. When running `service puppetserver status`, Puppet Server returned an unexpected error message: `puppetserver dead but pid file exists`.

When performing a clean installation of Puppet Server 2.5.0, no PID file is created, and `service puppetserver status` should return the expected `not running` message.

-   [SERVER-1455](https://tickets.puppetlabs.com/browse/SERVER-1455)
-   [EZ-84](https://tickets.puppetlabs.com/browse/EZ-84)

### Other changes

-   [SERVER-1310](https://tickets.puppetlabs.com/browse/SERVER-1310): Error messages in Puppet Server 2.5.0 use the same standard types as other Puppet projects.
-   [SERVER-1121](https://tickets.puppetlabs.com/browse/SERVER-1121): JRuby pool management code for the [Trapperkeeper Webserver Service][Trapperkeeper] is now its own open-source project, [puppetlabs/jrubyutils](https://github.com/puppetlabs/jruby-utils).
