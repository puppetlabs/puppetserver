---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

[Trapperkeeper]: https://github.com/puppetlabs/trapperkeeper
[service bootstrapping]: ./configuration.markdown#service-bootstrapping
[auth.conf]: ./config_file_auth.markdown

## Puppet Server 2.4

Released May 19, 2016.

This is a feature and bug-fix release of Puppet Server that also upgrades its included [Trapperkeeper][] framework from version 1.3.1 to 1.4.0.

This release also adds packages for Ubuntu 15.10 (Wily Werewolf) and 16.04 LTS (Xenial Xerus), and no longer includes packages for Fedora 21, which reached its end of life in December.

### New platforms: Ubuntu 15.10 (Wily Werewolf) and 16.04 LTS (Xenial Xerus)

Puppet Server 2.4.0 introduces [Puppet-built packages](https://docs.puppet.com/puppetserver/latest/install_from_packages.html) for Ubuntu 15.10 (Wily Werewolf) and 16.04 LTS (Xenial Xerus). For details about Puppet's package repositories, see the [Puppet Collections documentation](https://docs.puppet.com/puppet/latest/reference/puppet_collections.html).

-   [SERVER-1182](https://tickets.puppetlabs.com/browse/SERVER-1182)

### New feature: X.509-compliant certificate extensions can match authorization rules

When using the [new authorization methods introduced in version 2.2.0](./config_file_auth.markdown#aside-changes-to-authorization-in-puppet-server-220), Puppet Server relied on matching a requester's certificate name (certname) when authorizing HTTPS requests via SSL. Starting with version 2.4.0, Server can also match [authorization rules][auth.conf] to the content of [X.509 certificate extensions](https://access.redhat.com/documentation/en-US/Red_Hat_Certificate_System/8.0/html/Admin_Guide/Standard_X.509_v3_Certificate_Extensions.html).

Server 2.4.0 expands the syntax for [`allow` and `deny` parameters](./config_file_auth.markdown#allow-allow-unauthenticated-and-deny) in Server's `auth.conf` rules to allow for a map of `extensions` to match.

-   [TK-293: tk-auth should support x.509 extensions for authentication instead of just certname](https://tickets.puppetlabs.com/browse/TK-293)

Server 2.4.0 also reads custom OID shortname maps defined in Puppet's [`custom_trusted_oid_mapping.yaml`](https://docs.puppet.com/puppet/latest/reference/config_file_oid_map.html).

-   [SERVER-1150](https://tickets.puppetlabs.com/browse/SERVER-1150)
-   [SERVER-1245](https://tickets.puppetlabs.com/browse/SERVER-1245)

### New feature: Integrate with systemd services on Debian and Ubuntu

Puppet Server 2.4.0 adds integration with `systemd` on Debian 8 and newer, and Ubuntu 16.04 LTS.

-   [EZ-48](https://tickets.puppetlabs.com/browse/EZ-48)

### Improvement: Responses to unauthenticated HTTPS requests include less information

When responding to unauthorized HTTPS requests, previous versions of Puppet Server 2.x returned the requester's IP address and [authorization rule][auth.conf] in addition to logging the failed request. Puppet Server 2.4.0 removes this information from the response and directs the responder to consult the server logs for details.

-   [TK-360: Error messages returned to client should not include IP or rule blocking the request](https://tickets.puppetlabs.com/browse/TK-360)

### New feature: `always_retry_plugins` setting to configure Puppet feature caching

Puppet Server 2.4.0 respects the new [`always_retry_plugins` setting introduced in Puppet 4.5](https://docs.puppet.com/puppet/latest/reference/configuration.html#alwaysretryplugins), which determines how Puppet caches attempts to load Puppet resource types and features. However, Server changes this setting's value from its default (true) to false, in order to take advantage of additional caching for failures when loading types.

The `always_retry_plugins` setting also replaces the [`always_cache_features` setting](https://docs.puppet.com/puppet/4.5/reference/configuration.html#alwayscachefeatures), which is now deprecated. If you set `always_cache_features` to true in previous versions of Puppet Server, set `always_retry_plugins` to false.

-   [PUP-5482](https://tickets.puppetlabs.com/browse/PUP-5482)

### New feature: Expanded logging for certificate autosigning attempts

Starting with version 2.4.0, Puppet Server [logs](./configuration.markdown#logging) message and warnings when an autosign command generates STDERR output or returns a non-zero exit code. Server 2.4.0 also logs autosigning attempts at the INFO level, rather than DEBUG, to help make autosigning issues easier to diagnose without changing Server's logging level.

-   [SERVER-1187](https://tickets.puppetlabs.com/browse/SERVER-1187)

### Bug fix: Closed memory leak when restarting Server via SIGHUP

The Trapperkeeper components included with Puppet Server 2.3.x leaked a small amount of memory when [restarting Server with a HUP signal](./restarting.markdown). Puppet Server 2.4.0 includes updated components that resolve this issue.

-   [TK-372: Jetty JMX Memory Leak in TK WS J9](https://tickets.puppetlabs.com/browse/TK-372)

### Bug fix: Implement DELETE request handling on the `certificate_reqest` endpoint

Unlike the Ruby Puppet master, previous versions of Puppet Server couldn't handle [DELETE requests to the `certificate_request` endpoint](https://docs.puppet.com/puppet/latest/reference/http_api/http_certificate_status.html#delete), even if authorization rules allowed for them. Server 2.4.0 resolves this by handling authorized DELETE requests in the same way that the Ruby master does.

-   [SERVER-977](https://tickets.puppetlabs.com/browse/SERVER-977)

### Bug fixes: Certificate status endpoint behaviors

Puppet Server 2.4.0 resolves these issues with the [`certificate_status` endpoint](https://docs.puppet.com/puppet/latest/reference/http_api/http_certificate_status.html):

-   **Handle nil values in `desired_state` more gracefully ([SERVER-542](https://tickets.puppetlabs.com/browse/SERVER-542)):** If the `desired_state` of a PUT request to the `certificate_status` endpoint was nil, previous versions of Server threw a NullPointerException. Server 2.4.0 resolves this issue.
-   **Respect asterisks in `certificate_statuses` requests ([SERVER-864](https://tickets.puppetlabs.com/browse/SERVER-864)):** Previous versions of Server wouldn't return a list of certificates to authenticated `certificate_statuses` requests if the request included an asterisk (`*`). Server 2.4.0 resolves this issue.

### Other issues

-   **Remove hyphens in `puppet-server`:** We've [changed the name of our GitHub repository](https://tickets.puppetlabs.com/browse/SERVER-1206) from `puppet-server` to `puppetserver` and [removed the hyphen from many other references](https://tickets.puppetlabs.com/browse/SERVER-392).
-   **Log Ruby backtraces ([SERVER-1273](https://tickets.puppetlabs.com/browse/SERVER-1273)):** Previous versions of Server didn't log Ruby backtraces. Server 2.4.0 does, just like a Ruby Puppet master.
-   **Don't override the service startup timeout ([SERVER-557](https://tickets.puppetlabs.com/browse/SERVER-557)):** Previous versions of Server 2.x overrode the default 5-minute service startup timeout with a value of 120 seconds. Server 2.4.0 removes this override.
-   **Extend the default `ca_ttl` ([SERVER-615](https://tickets.puppetlabs.com/browse/SERVER-615)):** Server 2.4.0 enforces a maximum time-to-live of 50 years (1,576,800,000 seconds) on `puppet.conf`'s [`ca_ttl` setting](https://docs.puppet.com/puppet/latest/reference/configuration.html#cattl).

### All changes

* [All Puppet Server issues targeted at this release](https://tickets.puppetlabs.com/issues/?jql=project%20%3D%20SERVER%20AND%20fixVersion%20%3D%20%22SERVER%202.4.0%22%20ORDER%20BY%20updated%20DESC%2C%20priority%20DESC%2C%20created%20ASC)
