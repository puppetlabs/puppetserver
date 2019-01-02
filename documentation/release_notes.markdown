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

## Puppet Server 6.1.0

Released 18 December 2018

### Enhancements

- Puppet Server 6.1.0 upgrades to JRuby 9.2.0.0. This version implements the Ruby 2.5 interface. It is backwards compatible, but will issue a warning for Ruby language features that have been deprecated. The major warning that users will see is `warning: constant ::Fixnum is deprecated`. Upgrading to this version of JRuby means that the Ruby interface has the same version as the Puppet agent. This version of JRuby is faster than previous versions under certain conditions. [SERVER-2381](https://tickets.puppetlabs.com/browse/SERVER-2381)
- Puppet Server now has experimental support for Java 11 for users that run from source or build their own packages. This has been tested with low level tests but does not work when installed from official packages. Consequently, we consider this support "experimental", with full support coming later in 2019 for the latest long term supported version of Java. [SERVER-2315](https://tickets.puppetlabs.com/browse/SERVER-2315).
- The `puppetserver ca` command now provides useful errors on connection issues and returns debugging information. [SERVER-2317](https://tickets.puppetlabs.com/browse/SERVER-2317)
- The `puppetserver ca` tool now prefers the `server_list` setting in `puppet.conf` for users that have created their own high availability configuration using this feature. [SERVER-2392](https://tickets.puppetlabs.com/browse/SERVER-2392)

### Resolved issues

- The `puppetserver ca` command no longer has the wrong default value for the `$server` setting. Previously the `puppetserver ca` tool defaulted to `$certname` when connecting to the server, while the agent defaulted to `puppet`. The `puppetserver ca` tool now has the same default for `$server` as the agent. It will also honor the settings within the agent section of the `puppet.conf` file. [SERVER-2354](https://tickets.puppetlabs.com/browse/SERVER-2354)
- Jetty no longer reports its version. [TK-473](https://tickets.puppetlabs.com/browse/TK-473)


## Puppet Server 6.0.2

Released 23 October 2018

### New features

- The CA service and the CA proxy service (in PE) now have their own entries in the status endpoint output and can be queried as "ca" and "ca-proxy" respectively. [SERVER-2350](https://tickets.puppetlabs.com/browse/SERVER-2350)


## Puppet Server 6.0.1

Released 2 October 2018

### New features

- Puppet Server now creates a default `ca.conf` file when installed, both in open source Puppet and Puppet Enterprise. CA settings such as `allow-subject-alt-names` should be configured in the `certificate-authority` section of this file. ([SERVER-2372](https://tickets.puppetlabs.com/browse/SERVER-2327))

- The `puppetserver ca generate` command now has a flag `--ca-client` that will generate a certificate offline -- not using the CA API -- that is authorized to talk to that API.  This can be used to regenerate the master's host cert, or create certs for distribution to other CA nodes that need administrative access to the CA, such as the ability to sign and revoke certs. This command should only be used while Puppet Server is offline, to avoid conflicts with cert serials. ([SERVER-2320](https://tickets.puppetlabs.com/browse/SERVER-2320))

- The Puppet Server CA can now sign certificates with IP alt names in addition to DNS alt names (if signing certs with alt names is enabled). ([SERVER-2267](https://tickets.puppetlabs.com/browse/SERVER-2267)


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
