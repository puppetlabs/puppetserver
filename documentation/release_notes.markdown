---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

[Trapperkeeper]: https://github.com/puppetlabs/trapperkeeper
[service bootstrapping]: ./configuration.markdown#service-bootstrapping
[auth.conf]: ./config_file_auth.markdown
[puppetserver.conf]: ./config_file_puppetserver.markdown
[product.conf]: ./config_file_product.markdown

## Puppet Server 6.8.0

Released 14 January 2020

### New features

- When signing or generating certificates, you can now set the certificate time to live, either with a command line option or by specifying the key directly in the HTTP API. The time unit defaults to seconds, but you can specify a different time unit with any of time unit markers accepted in Puppet [configuration](https://puppet.com/docs/puppet/latest/configuration.html#configuration-settings).

  The `puppetserver ca sign` and `puppetserver ca generate` commands accept a `--ttl` flag to set certificate time to live. This setting determines how long the resulting certificate is valid for.

  Alternatively, you can set the time in the `certificate-status` API endpoint in the request body under the key `cert_ttl`. [SERVER-2678](https://tickets.puppetlabs.com/browse/SERVER-2678)

### Resolved issues

- Puppet Server no longer issues HTTP 503 responses to agents older than Puppet 5.3, which can't react to these responses. This allows the `max-queued-requests` setting to be used safely with older agents. [SERVER-2405](https://tickets.puppetlabs.com/browse/SERVER-2405)

## Puppet Server 6.7.2

Released 19 November 2019

This version contains minor security fixes.

## Puppet Server 6.7.1

Released 15 October 2019

### Resolved Issues

- Puppet Server can no longer be configured to accept SSLv3 traffic. [SERVER-2654](https://tickets.puppetlabs.com/browse/SERVER-2654)

## Puppet Server 6.7.0

Released 1 October 2019

### New feature

- Puppet Server packages are now available for Debian 10. These packages require Java 11 to be installed, rather than Java 8. [SERVER-2613](https://tickets.puppetlabs.com/browse/SERVER-2613)

### Resolved Issues

- Puppet Server now synchronizes write access to the CRL, so that each revoke request updates the CRL in succession, instead of concurrently. This prevents corruption of the CRL due to competing requests.

## Puppet Server 6.6.0

Released 17 September 2019

### New features

- Puppet Server no longer hardcodes Java's egd parameter. Users may manage the value via JAVA_ARGS or JAVA_ARGS_CLI in the defaults file. [SERVER-2602](https://tickets.puppetlabs.com/browse/SERVER-2602)

- RedHat 7 FIPS mode packages are now available for `puppetserver`. [SERVER-2555](https://tickets.puppetlabs.com/browse/SERVER-2555)

- Puppet Server now lists plan content from your modules, just as it does task content. [SERVER-2543](https://tickets.puppetlabs.com/browse/SERVER-2543)

- You can now enable sending a list of all the Hiera keys looked up during compile to PuppetDB, via the `jruby-puppet.track-lookups` setting in `puppetserver.conf`. This is currently only used by CD4PE. [SERVER-2538](https://tickets.puppetlabs.com/browse/SERVER-2538)

- Added the `/puppet-admin-api/v1/jruby-pool/thread-dump` endpoint, which returns a thread dump of running JRuby instances, if `jruby.management.enabled` has been set to `true` in the JVM running Puppet Server. See [Admin API: JRuby Pool](./admin-api/v1/jruby-pool.markdown#get-puppet-admin-apiv1jruby-poolthread-dump) for details. [SERVER-2193](https://tickets.puppetlabs.com/browse/SERVER-2193)

- Puppet Server now runs with JRuby 9.2.8.0. [SERVER-2388](https://tickets.puppetlabs.com/browse/SERVER-2588)
 
- The `puppetserver ca import` command now initializes an empty CRL for the intermediate CA if one is not provided in the `crl-chain` file. [SERVER-2522](https://tickets.puppetlabs.com/browse/SERVER-2552)

### Resolved issues

- Puppet Server can now be reloaded and run with multiple JRuby instances when running under Java 11. This change affects the packaging of Puppet Server. If you are running Puppet Server from source, you must add `facter.jar`, provided by the `puppet-agent` package, to the classpath when starting Puppet Server with Java. [SERVER-2423](https://tickets.puppetlabs.com/browse/SERVER-2423)

-Puppet Server's CA can now handle keys in the PKCS#8 format, which is required when running in FIPS mode. [SERVER-2019](https://tickets.puppetlabs.com/browse/SERVER-2019)


## Puppet Server 6.5.0

Released 22 July 2019

### New features

- The default for the `cipher-suites` setting in the webserver section of `webserver.conf` has been updated. Previously, the defaults included 11 cipher suites, including 4 `TLS_RSA_*` cipher suites. Now the defaults include all cipher suites usable on a RHEL 7 FIPS-enabled server, our target platform for FIPS certification, except for `TLS_RSA_*` ciphers. Additionally, Puppet Server emits warnings if any `TLS_RSA_*` ciphers are explicitly enabled in the `cipher-suites` setting.

To avoid potentially breaking clients that can use only `TLS_RSA_*` ciphers, the `webserver.conf` file now includes an explicit `cipher-suites` setting that adds the previously enabled `TLS_RSA_*` ciphers to the new implicit `cipher-suites` setting. This has three effects:

  1. Older clients that require the `TLS_RSA_*` ciphers will continue to work.
  2. Puppet Server generates warnings in the logs that the `TLS_RSA_*` ciphers are enabled.
  3. Puppet Server generates warnings in the logs if ciphers enumerated in the `cipher-suites` setting are not available on that specific OS. These warnings can be safely silenced by editing the `cipher-suites` setting and removing the unavailable ciphers.

  A future version of Puppet Server will remove the `cipher-suites` setting in `webserver.conf`. This will break any clients that still require the `TLS_RSA_*` ciphers.

  In advance of this change, update any clients that still require the `TLS_RSA_*` ciphers to clients that can use more recent ciphers, and remove the `cipher-suites` setting in `webserver.conf`.

  This update also removes the `so-linger-seconds` configuration setting. This setting is now ignored and a warning is issued if it is set. See Jetty's [so-linger-seconds](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/3.0.1/doc/jetty-config.md#so-linger-seconds) for removal details.

  See [SERVER-2576](https://tickets.puppetlabs.com/browse/SERVER-2576) for further details.

- You can now specify a `--certname` flag with the `puppetserver ca list` command, which limits the output to information about the requested cert and logs an error if the requested cert does not exist in any form. [SERVER-2589](https://tickets.puppetlabs.com/browse/SERVER-2589)

- You can now specify a log level for the logs collected by the new catalog compilation endpoint during compilation. See the [catalog endpoint docs](https://puppet.com/docs/puppetserver/6.4/puppet-api/v4/catalog.html) for information. [SERVER-2520](https://tickets.puppetlabs.com/browse/SERVER-2520)

- In this release, performance in `puppetserver` commands is improved. Running `puppetserver gem`, `puppetserver irb`, and other Puppet Server CLI commands are 15-30 percent faster to start up. Service starting and reloading should see similar improvements, along with some marginal improvements to top-end performance, especially in environments with limited sources of entropy.

- Building Puppet Server outside our network is now slightly easier.

- Prior to this release, an unnecessary and deprecated version of Facter was shipped in the `puppetserver` package. This has been removed.

- Cert and CRL bundles no longer need to be in any specific order. By default, the leaf instances still come first, descending to the root, which are last. [SERVER-2465](https://tickets.puppetlabs.com/browse/SERVER-2465)


## Puppet Server 6.4.0

Released 19 April 2019

### New features

- This release adds a new API endpoint to `/puppet/v3/environment_transports`. This endpoint lists all of the available network transports from modules and is for use with the Agentless Catalog Executor. [SERVER-2467](https://tickets.puppetlabs.com/browse/SERVER-2467)


## Puppet Server 6.3.0

Released 26 March 2019

### New features

- Puppet Server has a new endpoint for catalog retrieval, allowing more options than the previous endpoint. This endpoint is controlled by `tk-auth`, and by default is not generally accessible. It is an API that integrators can use to provide [functionality similar to `puppet master --compile`](https://tickets.puppetlabs.com/browse/PUP-9055). For details on the API, see the [Puppet API catalog](https://github.com/puppetlabs/puppetserver/blob/master/documentation/puppet-api/v4/catalog.markdown). This endpoint is intended for use by other Puppet services. [SERVER-2434](https://tickets.puppetlabs.com/browse/SERVER-2434) 

### Enhancements

- The CA's `certificate_status` endpoint now returns additional information for custom integration. [SERVER-2370](https://tickets.puppetlabs.com/browse/SERVER-2370)


## Puppet Server 6.2.1

Released 20 February 2019.

This release contains resolved issues.

### Resolved issues

- Updated bouncy-castle to 1.60 to fix security issues. [SERVER-2431](https://tickets.puppetlabs.com/browse/SERVER-2431)

## Puppet Server 6.2.0

Released 23 January 2019.

This release contains new features and resolved issues.

### New features

- The `puppetserver ca` tool now respects the `server_list` setting in `puppet.conf` for those users that have created their own high availability configuration using that feature. [SERVER-2392](https://tickets.puppetlabs.com/browse/SERVER-2392) 

- The EZBake configs now allow you to specify `JAVA_ARGS_CLI`, which is used when using `puppetserver` subcommands to configure Java differently from what is needed for the service. This was used by the CLI before, but as an environment variable only, not as an EZBake config option. [SERVER-2399](https://tickets.puppetlabs.com/browse/SERVER-2399)

### Resolved issues

-  A dependency issue caused puppetserver 6.1.0 to fail with OpenJDK 11. This has been fixed and Puppet Server packages can now start under Java 11. [SERVER-2404](https://tickets.puppetlabs.com/browse/SERVER-2404)


## Puppet Server 6.1.0

Released 18 December 2018

### New features

- The CA service and the CA proxy service (in PE) now have their own entries in the status endpoint output and can be queried as "ca" and "ca-proxy" respectively. [SERVER-2350](https://tickets.puppetlabs.com/browse/SERVER-2350)

- Puppet Server now creates a default `ca.conf` file when installed, both in open source Puppet and Puppet Enterprise. CA settings such as `allow-subject-alt-names` should be configured in the `certificate-authority` section of this file. ([SERVER-2372](https://tickets.puppetlabs.com/browse/SERVER-2327))

- The `puppetserver ca generate` command now has a flag `--ca-client` that will generate a certificate offline -- not using the CA API -- that is authorized to talk to that API.  This can be used to regenerate the master's host cert, or create certs for distribution to other CA nodes that need administrative access to the CA, such as the ability to sign and revoke certs. This command should only be used while Puppet Server is offline, to avoid conflicts with cert serials. ([SERVER-2320](https://tickets.puppetlabs.com/browse/SERVER-2320))

- The Puppet Server CA can now sign certificates with IP alt names in addition to DNS alt names (if signing certs with alt names is enabled). ([SERVER-2267](https://tickets.puppetlabs.com/browse/SERVER-2267)


### Enhancements

- Puppet Server 6.1.0 upgrades to JRuby 9.2.0.0. This version implements the Ruby 2.5 interface. It is backwards compatible, but will issue a warning for Ruby language features that have been deprecated. The major warning that users will see is `warning: constant ::Fixnum is deprecated`. Upgrading to this version of JRuby means that the Ruby interface has the same version as the Puppet agent. This version of JRuby is faster than previous versions under certain conditions. [SERVER-2381](https://tickets.puppetlabs.com/browse/SERVER-2381)

- Puppet Server now has experimental support for Java 11 for users that run from source or build their own packages. This has been tested with low level tests but does not work when installed from official packages. Consequently, we consider this support "experimental", with full support coming later in 2019 for the latest long term supported version of Java. [SERVER-2315](https://tickets.puppetlabs.com/browse/SERVER-2315).

- The `puppetserver ca` command now provides useful errors on connection issues and returns debugging information. [SERVER-2317](https://tickets.puppetlabs.com/browse/SERVER-2317)

- The `puppetserver ca` tool now prefers the `server_list` setting in `puppet.conf` for users that have created their own high availability configuration using this feature. [SERVER-2392](https://tickets.puppetlabs.com/browse/SERVER-2392)

### Resolved issues

- The `puppetserver ca` command no longer has the wrong default value for the `$server` setting. Previously the `puppetserver ca` tool defaulted to `$certname` when connecting to the server, while the agent defaulted to `puppet`. The `puppetserver ca` tool now has the same default for `$server` as the agent. It will also honor the settings within the agent section of the `puppet.conf` file. [SERVER-2354](https://tickets.puppetlabs.com/browse/SERVER-2354)
- Jetty no longer reports its version. [TK-473](https://tickets.puppetlabs.com/browse/TK-473)

## Puppet Server 6.0.0

Released 18 September 2018

This Puppet Server release provides a new workflow and API for certificate issuance. By default, the server now generates a root and intermediate signing CA cert, rather than signing everything off the root. If you have an external certificate authority, you can generate an intermediate signing CA from it instead, and a new `puppetserver ca` subcommand puts everything into its proper place.

### New features

- There is now a CLI command for setting up the certificate authority, called `puppetserver ca`. See [Puppet Server: Subcommands](/puppetserver/latest/subcommands.html) for more information. ([SERVER-2172](https://tickets.puppetlabs.com/browse/SERVER-2172))

- For fresh installs, the Puppet master's cert is now authorized to connect to the `certificate_status` endpoint out of the box. This allows the new CA CLI tool to perform CA tasks via Puppet Server's CA API. ([SERVER-2308](https://tickets.puppetlabs.com/browse/SERVER-2308)) Note that upgrades will need to instead whitelist the master's cert for these endpoints, see [Puppet Server: Subcommands#ca](/puppetserver/latest/subcommands.html#ca).

- Puppet Server now has a setting called `allow-authorization-extensions` in the `certificate-authority` section of its config for enabling signing certs with authorization extensions. It is false by default. ([SERVER-2290](https://tickets.puppetlabs.com/browse/SERVER-2290))

- Puppet Server now has a setting called `allow-subject-alt-names` in the `certificate-authority` section of its config for enabling signing certs with subject alternative names. It is false by default. ([SERVER-2278](https://tickets.puppetlabs.com/browse/SERVER-2278))

- The `puppetserver ca` CLI now has an `import` subcommand for installing key and certificate files that you generate, for example, when you have an external root CA that you need Puppet Server's PKI to chain to. ([SERVER-2261](https://tickets.puppetlabs.com/browse/SERVER-2261))

- We've added an infrastructure-only CRL in addition to the full CRL, that provides a list of certs that, when revoked, should be added to a separate CRL (useful for specifying special nodes in your infrastructure like compile masters). You can configure Whether this special CRL or the default CRL are distributed to agents. ([SERVER-2231](https://tickets.puppetlabs.com/browse/SERVER-2231))

- Puppet Server now bundles its `JRuby jar` inside the main uberjar. This means the `JRUBY_JAR` setting is no longer valid, and a warning will be issued if it is set.
([SERVER-2157](https://tickets.puppetlabs.com/browse/SERVER-2157))

- Puppet Server 6.0 uses JRuby 9K, which implements Ruby language version 2.3 Server-side gems that were installed manually with the `puppetserver gem` command or using the `puppetserver_gem` package provider might need to be updated to work with JRuby 9K. Additionally, if `ReservedCodeCache` or `MaxMetaspacesize` parameters were set in `JAVA_ARGS`, they might need to be adjusted for JRuby 9K. See the [known issues](/puppetserver/known_issues.html#server-side-ruby-gems-might-need-to-be-updated-for-upgrading-with-jruby-17) for more info. 

- The version of semantic_puppet has been updated in Puppet Server to ensure backwards compatibility in preparation for future major releases of Puppet Platform. ([SERVER-2132](https://tickets.puppetlabs.com/browse/SERVER-2132))

- Puppet Server 6.0 now uses JRuby 9k. This implements version 2.3 of the Ruby language. ([SERVER-2095](https://tickets.puppetlabs.com/browse/SERVER-2095))

### Resolved issues

- We've made server-side fixes for fully supporting intermediate CA capability. With this, CRL chains will be persisted when revoking certs. [SERVER-2205](https://tickets.puppetlabs.com/browse/SERVER-2205) For more details on the intermediate CA support in Puppet 6, see [Puppet Server: Intermediate CA](/puppetserver/latest/intermediate_ca.html).

### Known issues

Ruby’s native methods for spawning processes cause a fork of the JVM on most Linux servers, which in a large production environment causes Out of Memory errors at the OS level. Puppet Server provides a lighter weight way of creating sub-processes with its built-in execution helper `Puppet::Util::Execution.execute`. Use `Puppet::Util::Execution.execute` when writing Ruby-based functions, custom report processors, Hiera backends and faces. When writing custom providers, use the commands helper to determine suitability.
