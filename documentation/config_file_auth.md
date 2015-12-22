---
layout: default
title: "Puppet Server Configuration Files: auth.conf"
canonical: "/puppetserver/latest/config_file_auth.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[Puppet `auth.conf`]: /puppet/latest/reference/config_file_auth.html
[deprecated]: ./deprecated_features.html
[`puppetserver.conf`]: ./config_file_puppetserver.html

Puppet Server's `auth.conf` file contains rules for authorizing access to Puppet Server's HTTP API endpoints. For an overview, see [Puppet Server Configuration](./configuration.html).

The new Puppet Server authentication configuration and functionality is similar to the legacy method in that you define rules in a file named `auth.conf`, and Puppet Server applies the settings when a request's endpoint matches a rule. 

However, Puppet Server now has its own `auth.conf` file that uses a new HOCON format with different parameters, syntax, and functionality.

> ### Aside: Changes to Authorization in Puppet Server 2.2.0
> 
> Puppet Server 2.2.0 introduces a significant change in how it manages authentication to API endpoints. It uses [`trapperkeeper-authorization`][] for authentication, which is configured by rules and settings in Puppet Server's own `auth.conf`, with a HOCON configuration file format in a different location than the [Puppet `auth.conf`][] file.
>
> The older Puppet `auth.conf` file and whitelist-based authorization method are [deprecated][]. Puppet Server's new `auth.conf` file, documented below, also uses a different format for authorization rules.
>
> Puppet Server follows the following logic when determining whether to use the new or old authorization methods:
>
> * Requests to Puppet master service endpoints already manageable through the deprecated authorization methods and [Puppet `auth.conf`][] file --- such as `catalog`, `node`, and `report` --- use Puppet Server's new `auth.conf` rules **only** if the `use-legacy-auth-conf` setting in `puppet-server.conf` is set to `false`. If `use-legacy-auth-conf` is set to true (which is its default), Puppet Server warns you that the legacy authentication method is deprecated.
> * Requests to certificate status and administration endpoints use the new `auth.conf` rules **only** if the corresponding `client-whitelists` setting is empty or unspecified **and** the `authorization-required` flag is set to `true` (which is its default).
> * Requests to other certificate administration endpoints --- such as `certificate`, `certificate_request`, and `certificate_revocation_list` --- **always** use the new HOCON `auth.conf` rules in Puppet Server's `auth.conf` file. This happens regardless of the `client-whitelist`, `authorization-required`, or `use-legacy-auth-conf` settings, as versions of Puppet Server before 2.2.0 can't manage those endpoints.
>
> **Note:** You can also use the [`puppetlabs-puppet_authorization`](https://forge.puppetlabs.com/puppetlabs/puppet_authorization) module to manage the new `auth.conf` file's authorization rules in the new HOCON format, and the [`puppetlabs-hocon`](https://forge.puppetlabs.com/puppetlabs/hocon) module to use Puppet to manage HOCON-formatted settings in general.

You have two options when configuring how Puppet Server authenticates requests:

* If you opt into using Puppet Server's new, supported HOCON `auth.conf` file and authorization methods, use the parameters and rule definitions in the [HOCON Parameters](#hocon-parameters) section.
* If you continue using the deprecated Ruby [Puppet `auth.conf`][] file and authorization methods, see the [Deprecated Ruby Parameters](#deprecated-ruby-parameters) section.

## HOCON Parameters

Use the following parameters when writing or migrating custom authorization rules using the new HOCON format.

### `version`

The `version` parameter is required. In this initial release, the only supported value is `1`.

### `allow-header-cert-info`

> **Note:** Puppet Server ignores the setting of the same name in [`master.conf`](./config_file_master.html) in favor of this setting in the new `auth.conf` file. If you use the [deprecated][] authentication method and [Puppet `auth.conf`][] rules, you must instead configure this setting in `master.conf`.

This optional `authorization` section parameter determines whether to enable [external SSL termination](./external_ssl_termination.html) on all HTTP endpoints that Puppet Server handles, including those served by the "master" service, the certificate authority API, and the Puppet Admin API. It also controls how Puppet Server derives the user's identity for authorization purposes. The default value is `false`.

If this setting is `true`, Puppet Server ignores any presented certificate and relies completely on header data to authorize requests. 

> **Warning!** This is very insecure; **do not enable this parameter** unless you've secured your network to prevent **any** untrusted access to Puppet Server.

You cannot rename any of the `X-Client` headers when this setting is enabled, and you must specify identity through the `X-Client-Verify`, `X-Client-DN`, and `X-Client-Cert` headers.

For more information, see [External SSL Termination](./external_ssl_termination.html#disable-https-for-puppet-server) in the Puppet Server documentation and [Configuring the Authorization Service](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md#allow-header-cert-info) in the `trapperkeeper-authorization` documentation.

### `rules`

The required `rules` array of a Puppet Server's HOCON `auth.conf` file determines how Puppet Server responds to a request. Each element is a map of settings pertaining to a rule, and when Puppet Server receives a request, it evaluates that request against each rule looking for a match.

You define each rule by adding parameters to the rule's [`match-request`](#match-request) section. A `rules` array can contain as many rules as you need, each with a single `match-request` section.

If a request matches a rule in a `match-request` section, Puppet Server determines whether to allow or deny the request using the `rules` parameters that follow the rule's `match-request` section:

* At least one of:
    * [`allow`](#allow-allow-unauthenticated-and-deny)
    * [`allow-unauthenticated`](#allow-allow-unauthenticated-and-deny)
    * [`deny`](#allow-allow-unauthenticated-and-deny)
* [`sort-order`](#sort-order) (required)
* [`name`](#name) (required)

If no rule matches, Puppet Server denies the request by default and returns an HTTP 403/Forbidden response.

#### `match-request`

A `match-request` takes the following parameters:

* [`path`](#path-and-type) (required)
* [`type`](#path-and-type) (required)
* [`method`](#method)
* [`query-params`](#query-params-environment)

##### `path` and `type`

A `match-request` rule must have a `path` parameter, which returns a match when a request's endpoint URL starts with or contains the `path` parameter's value. The parameter can be a literal string or regular expression as defined in the required `type` parameter.

~~~ hocon
# Regular expression to match a path in a URL.
path: "^/puppet/v3/report/([^/]+)$"
type: regex

# Literal string to match the start of a URL's path.
path: "/puppet/v3/report/"
type: path
~~~

> **Note:** While the HOCON format doesn't require you to wrap all string values with double quotation marks, some special characters commonly used in regular expressions --- such as `*` --- break HOCON parsing unless the entire value is enclosed in double quotes.

##### `method`

If a rule contains the optional `method` parameter, Puppet Server applies that rule only to requests that use its value's listed HTTP methods. This parameter's valid values are `get`, `post`, `put`, `delete`, and `head`, provided either as a single value or array of values.

~~~ hocon
# Use GET and POST.
method: [get, post]

# Use PUT.
method: put
~~~

> **Note:** While the new HOCON format does not provide a direct equivalent to the [deprecated][] `method` parameter's `search` indirector, you can create the equivalent rule by passing GET and POST to `method` and specifying endpoint paths using the `path` parameter.

##### `query-params` (`environment`)

For endpoints on a Puppet 4 master, you can supply the `environment` as a query parameter suffix on the request's base URL. Use the optional `query-params` setting and provide the list of query parameters as an array to the setting's `environment` parameter.

For example, this rule would match a request URL containing the `environment=production` or `environment=test` query parameters:

~~~ hocon
query-params: {
    environment: [ production, test ]
}
~~~

> **Note:** For Puppet 3 master endpoints, the `environment` was represented as the first subpath in the URL instead of as a query parameter. As noted in the [Puppet 3 agent compatibility section](#puppet-3-agent-compatibility), Puppet Server translates incoming Puppet 3-style URLs to Puppet 4-style URLs before evaluating them against the new HOCON `auth.conf` rules, so the `query-params` approach above replaces environment-specific rules for both Puppet 3 and Puppet 4.

#### `allow`, `allow-unauthenticated`, and `deny`

After each rule's `match-request` section, it must also have an `allow`, `allow-unauthenticated`, or `deny` parameter. (You can set both `allow` and `deny` parameters for a rule, though Puppet Server always prioritizes `deny` over `allow` when a request matches both.) 

If a request matches the rule, Puppet Server checks the request's authenticated "name" (see [`allow-header-cert-info`](#allow-header-cert-info)) against these parameters to determine what to do with the request.

* **`allow-unauthenticated`**: If this Boolean parameter is set to `true`, Puppet Server allows the request --- even if it can't determine an authenticated name. **This is a potentially insecure configuration** --- be careful when enabling it. A rule with this parameter set to `true` can't also contain the `allow` or `deny` parameters.
* **`allow`**: This parameter can take a single string value or an array of them. The values can be:
    * An exact domain name, such as `www.example.com`.
    * A glob of names containing a `*` in the first segment, such as `*.example.com` or simply `*`. 
    * A regular expression surrounded by `/` characters, such as `/example/`.
    * A backreference to a regular expression's capture group in the `path` value, if the rule also contains a `type` value of `regex`. For example, if the path for the rule were `"^/example/([^/]+)$"`, you can make a backreference to the first capture group using a value like `$1.domain.org`.

    If the request's authenticated name matches the parameter's value, Puppet Server allows it.
* **`deny`**: This parameter can take the same types of values as the `allow` parameter, but refuses the request if the authenticated name matches --- even if the rule contains an `allow` value that also matches.

> **Note:** The new authentication method introduced in Puppet Server 2.2.0 does not support, or provide an equivalent to, the `allow_ip` or `deny_ip` parameters in the [deprecated][] [Puppet `auth.conf`][] rule format.
>
> Also, in the HOCON Puppet Server authentication method, there is no directly equivalent behavior to the [deprecated][] `auth` parameter's `on` value.

#### `sort-order`

After each rule's `match-request` section, the required `sort-order` parameter sets the order in which Puppet Server evaluates the rule by prioritizing it on a numeric value between 1 and 399 (to be evaluated before default Puppet rules) or 601 to 998 (to be evaluated after Puppet), with lower-numbered values evaluated first. Puppet Server secondarily sorts rules lexicographically by the `name` string value's Unicode code points.

~~~ hocon
sort-order: 1
~~~

#### `name`

After each rule's `match-request` section, this required parameter's unique string value identifies the rule to Puppet Server. The `name` value is also written to server logs and error responses returned to unauthorized clients.

~~~ hocon
name: "my path"
~~~

> **Note:** If multiple rules have the same `name` value, Puppet Server will fail to launch.

## Puppet 3 Agent Compatibility

Puppet 4 changed the URL structure for Puppet master and CA endpoints. For more information, see:

* [Puppet 4 HTTPS API documentation](/puppet/latest/reference/http_api/http_api_index.html)
* [Puppet 3 HTTPS API documentation](/references/3.8.0/developer/file.http_api_index.html)
* [Puppet 4 `auth.conf` documentation](/puppet/latest/reference/config_file_auth.html)
* [Puppet 3 `auth.conf` documentation](/puppet/3.8/reference/config_file_auth.html)

Puppet Server allows agents to make requests at the old URLs and internally translates them as requests to the new endpoints. However, rules in `auth.conf` that match Puppet 3-style URLs will have _no effect._ For more information, see [Backward Compatibility With Puppet 3 Agents](./compatibility_with_puppet_agent.markdown).

## Related Configuration Settings

For backward compatibility, settings in [`puppetserver.conf`][] also control whether to use the new Puppet Server authorization method for certain endpoints:

* `use-legacy-auth-conf` in the `jruby-puppet` section: If `true`, Puppet Server uses the Ruby authorization methods and  [Puppet `auth.conf`][] rule format and warns you that this is [deprecated][]. If `false`, Puppet Server uses the new authorization method and HOCON `auth.conf` format. Default: `true`.
* `authorization-required` and `client-whitelist` in the `puppet-admin` section: If `authorization-required` is set to `false` or `client-whitelist` has at least one entry, Puppet Server authorizes requests to Puppet Server's administrative API according to the parameters' values. See the [`puppetserver.conf` documentation][`puppetserver.conf`] for more information on these settings. If `authorization-required` is set to `true` or not set and `client-whitelist` is set to an empty list or not set, Puppet Server authorizes requests to Puppet Server's administrative API using the authorization method introduced in Puppet Server 2.2.0.
* `certificate-status.authorization-required` and `certificate-status.client-whitelist` in the `certificate-authority` section: If `authorization-required` is set to `false` or `client-whitelist` has one or more entries, Puppet Server handles requests made to its [Certificate Status](/puppet/latest/reference/http_api/http_certificate_status.html) API according to the parameters' values. See the [`ca.conf` documentation](./config_file_ca.html) for more information on these settings. If `authorization-required` is set to `true` or not set and the `client-whitelist` is set to an empty list or not set, Puppet Server authorizes requests using the authorization method introduced in Puppet Server 2.2.0.

## Deprecated Ruby Parameters

> **Deprecation Note:** The legacy [Puppet `auth.conf`][] rules for the master endpoints, and client whitelists for the Puppet admin and certificate status endpoints, are [deprecated][]. Convert your configuration files to the HOCON formats using the equivalent [HOCON parameters](#hocon-parameters).

### `path`

Rules with a `path` parameter apply only to endpoints with URLs that start with the parameter's value. In the [deprecated][] [Puppet `auth.conf`][] rule format, start the `path` value with a tilde (`~`) character to indicate that it contains a regular expression.

~~~
# Regular expression to match a path in a URL.
path ~ ^/puppet/v3/report/([^/]+)$

# Literal string to match at the start of a URL's path.
path /puppet/v3/report/
~~~

### `method`

If a rule contains the `method` parameter, it only applies to requests that use the value's corresponding HTTP methods. In the [deprecated][] [Puppet `auth.conf`][] rule format, use indirector names for the `method` value:

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

### `environment`

For endpoints on a Puppet 4 master, you can supply the `environment` as a query parameter suffix on the request's base URL. In a [deprecated][] [Puppet `auth.conf`][] rule, the `environment` parameter adds a comma-separated list of query parameters as a suffix to the base URL.

~~~
environment: production,test
~~~

> **Note:** For Puppet 3 master endpoints, the `environment` was represented as the first subpath in the URL instead of as a query parameter. As noted in the [Puppet 3 agent compatibility section](#puppet-3-agent-compatibility), Puppet Server translates incoming Puppet 3-style URLs to Puppet 4-style URLs before evaluating them.

### `auth`

In a [deprecated][] [Puppet `auth.conf`][] rule, the `auth` parameter specifies whether a rule applies only to authenticated clients (`on`; that is, those that provide a client certificate), only to unauthenticated clients (`off`), or to both (`any`).

For example, the following deprecated Puppet `auth.conf` rule matches all clients, including those that do not have to be authenticated:

~~~
auth: any
~~~

> **Note:** In the new HOCON `auth.conf` file, there is no directly equivalent behavior to the deprecated `auth` parameter's `on` value.

### `allow-header-cert-info`

If you've enabled the new authentication method introduced in Puppet Server 2.2.0, Puppet Server ignores the setting of the same name in the [deprecated][] [`master.conf`](./config_file_master.html) in favor of this setting in Puppet Server's new HOCON `auth.conf` file. If you use the deprecated authentication method and [Puppet `auth.conf`][] rules and want to configure this setting, you **must** do so in `master.conf`.
