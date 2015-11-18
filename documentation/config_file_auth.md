---
layout: default
title: "Puppet Server Configuration Files: auth.conf"
canonical: "/puppetserver/latest/config_file_auth.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[Puppet `auth.conf`]: /puppet/latest/reference/config_file_auth.html
[deprecated]: ./deprecated_features.html
[`puppetserver.conf`]: ./config_file_puppetserver.html

The `auth.conf` file contains rules for authorizing access to Puppet Server's HTTP API endpoints. For an overview, see [Puppet Server Configuration](./configuration.html).

The new Puppet Server authentication configuration and functionality is similar to the legacy method in that you define rules in `auth.conf`, and Puppet Server applies the settings when a request's endpoint matches a rule. 

However, the new HOCON format provides different parameters and syntax, and the new method provides slightly different functionality. The following sections describe how to configure both methods.

> ### Aside: Changes to Authorization in Puppet Server 2.2.0
> 
> Puppet Server 2.2.0 introduces a significant change in how it manages authentication to API endpoints. It uses [`trapperkeeper-authorization`][] for authentication, which is configured by rules and settings in Puppet Server's own `auth.conf`, with a HOCON configuration file format in a different location than the [Puppet `auth.conf`][] file.
>
> The older Puppet `auth.conf` file and whitelist-based authorization method are [deprecated][]. Puppet Server's new `auth.conf` file, documented below, also uses a different format for authorization rules. Additionally, there are several conditions that can affect whether Puppet Server uses the new or old authorization methods:
>
> * Requests to Puppet master service endpoints already manageable through the older authorization methods and [Puppet `auth.conf`][] file---such as catalog, node, and report---will use Puppet Server's new `auth.conf` rules **only** if the `use-legacy-auth-conf` setting in `puppet-server.conf` is set to false. Also, Puppet Server warns you that the legacy authentication method is deprecated if `use-legacy-auth-conf` is set to true (which is its default).
> * Requests to certificate status and administration endpoints will use the new `auth.conf` rules **only** if the corresponding `client-whitelists` setting is empty or unspecified and the `authorization-required` flag is set to true (which is its default).
> * Requests to other certificate administration endpoints---such as certificate, certificate_request, and certificate_revocation_list---will **always** use the new `auth.conf` rules. This happens regardless of the `client-whitelist`, `authorization-required`, or `use-legacy-auth-conf` settings, as versions of Puppet Server before 2.2.0 can't manage those endpoints.
>
> **Note:** You can also use the [`puppetlabs-puppet_authorization`](https://forge.puppetlabs.com/puppetlabs/puppet_authorization) module to manage the new `auth.conf` file's authorization rules in the new HOCON format, and the [`puppetlabs-hocon`](https://forge.puppetlabs.com/puppetlabs/hocon).

## Parameters

Use the following parameters when writing or migrating custom authorization rules.

### `path`

Rules with a `path` parameter apply only to endpoints with URLs that start with the parameter's value.

In a HOCON `auth.conf` rule, distinguish between regular expressions and literal strings by explicitly stating the `type` parameter.

~~~
# Regular expression to match a path in a URL.
path: "^/puppet/v3/report/([^/]+)$"
type: regex

# Literal string to match at the start of a URL's path.
path: "/puppet/v3/report/"
type: path
~~~

> **Note:** Enclose the path value within double quotes. While the HOCON format does not always require wrapping string values with double quotes, special characters commonly used in regular expressions (such as `*`) break HOCON parsing unless the entire value is surrounded by double quotes.

In the deprecated `auth.conf` format, start the `path` value with a tilde (`~`) character to indicate that it contains a regular expression.

~~~
# Regular expression to match a path in a URL.
path ~ ^/puppet/v3/report/([^/]+)$

# Literal string to match at the start of a URL's path.
path /puppet/v3/report/
~~~

### `method`

If a rule contains the `method` parameter, it only applies to requests that use the value's corresponding HTTP methods.

In a HOCON `auth.conf` rule, list the HTTP methods.

~~~
# Use GET and POST.
method: [get, post]

# Use PUT.
method: put
~~~

In the deprecated `auth.conf` format, use indirector names for the `method` value instead of the request's HTTP method:

Indirector | HTTP
-----------|------
find       | GET and POST
search     | GET and POST, for endpoints with names that end in "s" or "_search"
save       | PUT
destroy    | DELETE

For more details, see the [Puppet `auth.conf` documentation](/puppet/latest/reference/config_file_auth.html#method).

~~~
# Use GET and POST.
method: find

# Use PUT.
method: save
~~~

> **Note:** While the HOCON format does not provide a direct equivalent to the 'search' indirector, you can create the equivalent rule by passing GET and POST to `method` and specifying endpoint paths using the `path` parameter.

### `environment`

For endpoints on a Puppet 4 master, you can supply the `environment` as a query parameter suffix on the request's base URL.

In a HOCON rule, use the `query-params` setting and provide the list of query parameters as an array to the setting's `environment` parameter:

~~~
query-params: {
    environment: [ production, test ]
}
~~~

In a deprecated `auth.conf` rule, the `environment` parameter adds a comma-separated list of query parameters as a suffix to the base URL.

~~~
environment: production,test
~~~

> **Note:** For Puppet 3 master endpoints, the `environment` was represented as the first subpath in the URL instead of as a query parameter. As noted in the [Puppet 3 agent compatibility section](#puppet-3-agent-compatibility), Puppet Server translates incoming Puppet 3-style URLs to Puppet 4-style URLs before evaluating them against the new HOCON `auth.conf` rules, so the `query-params` approach above replaces environment-specific rules both for Puppet 3 and Puppet 4.

### `allow-unauthenticated` and `auth`

In the HOCON rule format, Puppet Server determines whether to allow or deny requests _after_ a rule is matched. By default, this authentication method refuses unauthenticated requests. You can force Puppet Server to allow requests from unauthenticated clients by setting the Boolean `allow-unauthenticated` parameter to true:

~~~
allow-unauthenticated: true
~~~

> **Note:** The authentication method introduced in Puppet Server 2.2 does not support the `allow_ip` or `deny_ip` parameters used in the deprecated `auth.conf` file.

In a deprecated `auth.conf` rule, the `auth` parameter specifies whether a rule applies only to authenticated clients (`on`; that is, those that provide a client certificate), only to unauthenticated clients (`off`), or to both (`any`).

For example, the following deprecated `auth.conf` rule matches all clients, including those that do not have to be authenticated:

~~~
auth: any
~~~

> **Note:** In the HOCON Puppet Server authentication method, there is no directly equivalent behavior to the deprecated `auth` parameter's `on` value.

### `allow-header-cert-info`

> **Note:** If you've enabled the new authentication method introduced in Puppet Server 2.2, Puppet Server ignores the setting of the same name in [`master.conf`](./config_file_master.html) in favor of this setting in the new `auth.conf` file. If you use the deprecated authentication method and legacy [Puppet `auth.conf`][] file, you must instead configure this setting in `master.conf`.

This setting determines whether to enable [external SSL termination](./external_ssl_termination.markdown) on all HTTP endpoints that Puppet Server handles, including those served by the "master" service, the certificate authority API, and the Puppet Admin API. It also controls how Puppet Server derives the user's identity for authorization purposes.

If this setting is enabled, Puppet Server ignores any presented certificate and relies completely on header data to authorize requests. **This is very insecure; do not do this unless you've secured your network to prevent _any_ untrusted access to Puppet Server.**

You cannot rename any of the `X-Client` headers when this setting is enabled. Identity must be specified through the `X-Client-Verify`, `X-Client-DN`, and `X-Client-Cert` headers.

For more information, see [External SSL Termination](./external_ssl_termination.html#disable-https-for-puppet-server) in the Puppet Server documentation and [Configuring the Authorization Service](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md#allow-header-cert-info) in the `trapperkeeper-authorization` documentation.

## Puppet 3 Agent Compatibility

Puppet 4 changed the URL structure for Puppet master and CA endpoints. For more information, see:

* [Puppet 4 HTTPS API documentation](/puppet/latest/reference/http_api/http_api_index.html)
* [Puppet 3 HTTPS API documentation](/references/3.8.0/developer/file.http_api_index.html)
* [Puppet 4 `auth.conf` documentation](/puppet/latest/reference/config_file_auth.html)
* [Puppet 3 `auth.conf` documentation](/puppet/3.8/reference/config_file_auth.html)

Puppet Server allows agents to make requests at the old URLs and internally translates them as requests to the new endpoints. However, rules in `auth.conf` that match Puppet 3-style URLs will have _no effect._ For more information, see [Backward Compatibility With Puppet 3 Agents](./compatibility_with_puppet_agent.markdown).

## Related Configuration Settings

> **Deprecation Note:** The `auth.conf` rules for the master endpoints, and client whitelists for the Puppet admin and certificate status endpoints, are deprecated. Convert your configuration files to the HOCON formats and configure the following settings to allow only the new authorization method.

For backward compatibility, settings in [`puppetserver.conf`][] also control whether to use the new Puppet Server authorization method for certain endpoints:

* `use-legacy-auth-conf` in the `jruby-puppet` section: If `true`, Puppet Server uses the Ruby authorization methods and  [Puppet `auth.conf`][] format and warns you that this is deprecated. If `false`, Puppet Server uses the new authorization method and HOCON `auth.conf` format. Default: `true`.
* `authorization-required` and `client-whitelist` in the `puppet-admin` section: If `authorization-required` is set to `false` or `client-whitelist` has at least one entry, Puppet Server authorizes requests to Puppet Server's administrative API according to the parameters' values. See the [`puppetserver.conf` documentation][`puppetserver.conf`] for more information on these settings. If `authorization-required` is set to `true` or not set and `client-whitelist` is set to an empty list or not set, Puppet Server authorizes requests to Puppet Server's administrative API using the authorization method introduced in Puppet Server 2.2.
* `certificate-status.authorization-required` and `certificate-status.client-whitelist` in the `certificate-authority` section: If `authorization-required` is set to `false` or `client-whitelist` has one or more entries, Puppet Server handles requests made to its [Certificate Status](/puppet/latest/reference/http_api/http_certificate_status.html) API according to the parameters' values. See the [`ca.conf` documentation](./config_file_ca.html) for more information on these settings. If `authorization-required` is set to `true` or not set and the `client-whitelist` is set to an empty list or not set, Puppet Server authorizes requests using the authorization method introduced in Puppet Server 2.2.
