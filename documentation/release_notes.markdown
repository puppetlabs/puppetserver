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


## Puppet Server 6.0.0

Released 18 September, 2018

This Puppet Server release provides a new workflow and API for certificate issuance. By default, the server will now generate a root and intermediate signing CA cert, rather than signing everything off the root. If you have an external certificate authority, you can generate an intermediate signing CA from it instead, and a new `puppetserver ca` subcommand will put everything put into its proper place

### New features

- There is now a CLI command for setting up the certificate authority, called `puppetserver ca`. See [Puppet Server: Intermediate CA](/puppetserver/latest/intermediate_ca.html) for more information. [(SERVER-2172](https://tickets.puppetlabs.com/browse/SERVER-2172))

- The Puppet master's cert is now authorized to connect to the `certificate-status` endpoint out of the box. This allows the new CA CLI tool to perform CA tasks via Puppet Server's CA API. ([SERVER-2308](https://tickets.puppetlabs.com/browse/SERVER-2308))

- Puppet Server now has a setting called `allow-authorization-extensions` in the `certificate-authority` section of its config for enabling signing certs with authorization extensions. It is false by default. ([SERVER-2290](https://tickets.puppetlabs.com/browse/SERVER-2290))

- Puppet Server now has a setting called `allow-subject-alt-names` in the `certificate-authority` section of its config for enabling signing certs with subject alternative names. It is false by default. ([SERVER-2278](https://tickets.puppetlabs.com/browse/SERVER-2278))

- The `puppetserver ca` CLI now has an `import` subcommand for installing key and certificate files that you generate, for example, when you have an external root CA that you need Puppet Server's PKI to chain to. ([SERVER-2261](https://tickets.puppetlabs.com/browse/SERVER-2261))

- We've added an infrastructure-only CRL in addition to the full CRL, that provides a list of certs that, when revoked, should be added to a separate CRL (useful for specifying special nodes in your infrastructure like compile masters). You can configure Whether this special CRL or the default CRL are distributed to agents. ([SERVER-2231](https://tickets.puppetlabs.com/browse/SERVER-2231))

- Puppet Server now bundles its `JRuby jar` inside the main uberjar. This means the `JRUBY_JAR` setting is no longer valid, and a warning will be issued if it is set.

    When upgrading from Puppet Server 5 using JRuby 1.7 (9k was optional in those releases), Server-side gems that were installed manually with the `puppetserver gem` command or using the `puppetserver_gem` package provider might need to be updated to work with the newer JRuby. In most cases gems do not have APIs that break when upgrading from the Ruby versions implemented between JRuby 1.7 and JRuby 9k, so there might be no necessary updates. However, two notable exceptions are that the autosign gem should be 0.1.3 or later and yard-doc must be 0.9 or later. 

    If you're working outside of lab environment, increase `ReservedCodeCache` to `512m` under normal load. If you're working with 6-12 JRuby instances (or a `max-requests-per-instance` value significantly less than 100k), run with a `ReservedCodeCache` of 1G. Twelve or more JRuby instances in a single server might require 2G or more. 

    Similar caveats regarding scaling `ReservedCodeCache` might apply if users are managing `MaxMetaspace`. ([SERVER-2157](https://tickets.puppetlabs.com/browse/SERVER-2157))

- The version of semantic_puppet has been updated in Puppet Server to ensure backwards compatibility in preparation for future major releases of Puppet Platform. ([SERVER-2132](https://tickets.puppetlabs.com/browse/SERVER-2132))

- Puppet Server 6.0 now uses JRuby 9k. This implements version 2.3 of the Ruby language. ([SERVER-2095](https://tickets.puppetlabs.com/browse/SERVER-2095))

### Bug fixes

- We've made server-side fixes for fully supporting intermediate CA capability. With this, CRL chains will be persisted when revoking certs. [SERVER-2205](https://tickets.puppetlabs.com/browse/SERVER-2205)



