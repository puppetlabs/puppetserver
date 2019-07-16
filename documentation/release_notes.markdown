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

## Puppet Server 6.0.5

Released 16 July 2019

### Bug fixes

In this release, performance in puppetserver commands is improved. Running `puppetserver gem`, `puppetserver irb`, and other Puppet Server CLI commands are 15-30 percent faster to start up. Service starting and reloading should see similar improvements, along with some marginal improvements to top-end performance, especially in environments with limited sources of entropy.

Building Puppet Server outside our network is now slightly easier.

Prior to this release, an unnecessary and deprecated version of Facter was shipped in the `puppetserver` package. This has been removed.

Cert and CRL bundles no longer need to be in any specific order. By default, the leaf instances still come first, descending to the root, which are last. [SERVER-2465](https://tickets.puppetlabs.com/browse/SERVER-2465)

## Puppet Server 6.0.4

Released 26 March 2019

### Bug fixes

- Updated bouncy-castle to 1.60 to fix security issues. [SERVER-2431](https://tickets.puppetlabs.com/browse/SERVER-2431)

## Puppet Server 6.0.3

Released 15 January 2019.

This release contains new features.

### New Features

- The `puppetserver ca` tool now respects the `server_list` setting in `puppet.conf` for those users that have created their own high availability configuration using that feature. [SERVER-2392](https://tickets.puppetlabs.com/browse/SERVER-2392) 
- The EZBake configs now allow you to specify `JAVA_ARGS_CLI`, which is used when using `puppetserver` subcommands to configure Java differently from what is needed for the service. This was used by the CLI before, but as an environment variable only, not as an EZBake config option. [SERVER-2399](https://tickets.puppetlabs.com/browse/SERVER-2399)

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
