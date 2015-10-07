---
layout: default
title: "Puppet Server Configuration Files: auth.conf"
canonical: "/puppetserver/latest/config_file_auth.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[new `auth.conf`]: ./conf_file_auth.html
[Puppet `auth.conf`]: /puppet/latest/reference/config_file_auth.html
[deprecated]: ./deprecated_settings.html
[`puppetserver.conf`]: ./conf_file_puppetserver.html

The `auth.conf` file contains rules for authorizing access to Puppet Server's HTTP API endpoints. For a broader overview of Puppet Server configuration, see the [configuration documentation](./configuration.html).

> **Deprecation Note:** As of version 2.2, Puppet Server can use [`trapperkeeper-authorization`][] for authentication, which is configured by rules and settings in `auth.conf` using a new HOCON configuration file format, and the new `conf.d` location for Puppet Server configuration files. The legacy [Puppet `auth.conf`][] whitelist-based authorization method and configuration file format are [deprecated][] and will be removed in a future version of Puppet Server.
>
> You can enable this new method by setting the `use-legacy-auth-conf` parameter in the `jruby-puppet` section to `false`. It defaults to `true`. 
>
> Puppet Server warns you that the legacy authentication method is deprecated if `use-legacy-auth-conf` is set to `true` or to its default.

The new Puppet Server authentication configuration and functionality is fundamentally similar to the legacy method: you define rules in `auth.conf`, and Puppet Server applies the settings when a request's endpoint matches a rule. 

However, the new HOCON format provides different parameters and syntax, and the new method provides slightly different functionality. The following sections document how to configure both methods.

## Parameters

Use the following parameters when writing or migrating custom authorization rules.

### `path`

Rules with a `path` parameter only apply to endpoints with URLs that start with the parameter's value.

In a HOCON `auth.conf` rule, distinguish between regular expressions and literal strings by explicitly stating the `type` parameter.

~~~
# Regular expression to match a path in a URL.
path: "^/puppet/v3/report/([^/]+)$"
type: regex

# Literal string to match at the start of a URL's path.
path: "/puppet/v3/report/"
type: path
~~~

> **Note:** Remember to delimit the contents of the `path` with double quotes for HOCON `auth.conf` rules. While the HOCON format does not always require wrapping string values with double quotes, special characters commonly used in regular expressions (such as `*`) break HOCON parsing unless the entire value is surrounded by double quotes.

The legacy `auth.conf` format uses the presence of a tilde (`~`) character to distinguish a regular expression `path` value from a literal string value.

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

The legacy `auth.conf` format uses indirector names for the `method` value instead of the request's HTTP method:

Indirector | HTTP
-----------|------
find       | GET and POST
search     | GET and POST, for endpoints whose names end in "s" or "_search"
save       | PUT
destroy    | DELETE

For more details, see the [Puppet `auth.conf` documentation](/puppet/latest/reference/config_file_auth.html#method).

~~~
# Use GET and POST.
method: find

# Use PUT.
method: save
~~~

> **Note:** While the HOCON format does not provide a direct equivalent to the 'search' indirector, you can pass GET and POST to `method` and specify endpoint paths using the `path` parameter.

### `environment`

For endpoints on a Puppet 4 master, you can supply the `environment` as a query parameter suffixed to the request's base URL.

In a HOCON rule, use the `query-params` setting and provide the list of query parameters as an array to the setting's `environment` parameter:

~~~
query-params: {
    environment: [ production, test ]
}
~~~

In a legacy `auth.conf` rule, the `environment` parameter suffixes a comma-separated list of query parameters to the base URL.

~~~
environment: production,test
~~~

> **Note:** For Puppet 3 master endpoints, the `environment` was represented as the first subpath in the URL instead of as a query parameter. As noted in the [Puppet 3 agent compatibility section](#puppet-3-agent-compatibility), Puppet Server translates incoming Puppet 3-style URLs to Puppet 4-style URLs before evaluating them against the new HOCON `auth.conf` rules, so the `query-params` approach above would replace environment-specific rules both for Puppet 3 and Puppet 4.

### `allow-unauthenticated` and `auth`

In the new Puppet Server rule format, Puppet Server determines whether to allow or deny requests _after_ a rule is matched. By default, this authentication method refuses unauthenticated requests. You can force Puppet Server to allow requests from unauthenticated clients by setting the Boolean `allow-unauthenticated` parameter to true:

~~~
allow-unauthenticated: true
~~~

> **Note:** The new authentication method introduced in Puppet Server 2.2 does not support the `allow_ip` or `deny_ip` parameters used in the legacy `auth.conf` file.

In a legacy `auth.conf` rule, the `auth` parameter specifies whether a rule applies only to authenticated clients (`on`; such as those that provide a client certificate), only to unauthenticated clients (`off`), or to both (`any`).

For example, this legacy `auth.conf` rule matches all clients, including those that do not have to be authenticated:

~~~
auth: any
~~~

> **Note:** In the new Puppet Server authentication method, there is no directly equivalent behavior to the legacy `auth` parameter's `on` value.

## Puppet 3 Agent Compatibility

Puppet 4 changed the URL structure for Puppet master and CA endpoints. For more information, see:

* [Puppet 4 HTTPS API documentation](/puppet/latest/reference/http_api/http_api_index.html)
* [Puppet 3 HTTPS API documentation](/references/3.8.0/developer/file.http_api_index.html)
* [Puppet 4 `auth.conf` documentation](/puppet/latest/reference/config_file_auth.html)
* [Puppet 3 `auth.conf` documentation](/puppet/3.8/reference/config_file_auth.html)

Puppet Server allows agents to make requests at the old URLs and internally translates them as requests to the new endpoints. However, rules in `auth.conf` that match Puppet 3-style URLs will have _no effect._ For more information, see the [Puppet agent compatibility](./compatibility_with_puppet_agent.markdown) documentation.

## Related Configuration Settings

> **Deprecation Note:** The legacy `auth.conf` rules for the "master" endpoints, and client whitelists for the Puppet admin and certificate status endpoints, are deprecated. Puppet recommends converting your configuration files to the new HOCON formats and configuring the below settings to allow only the new authorization method.

For backward compatibility, settings in [`puppetserver.conf`][] also control whether to use the new Puppet Server authorization method for certain endpoints:

* `use-legacy-auth-conf` in the `jruby-puppet` section: If `true`, Puppet Server uses the legacy Ruby authorization methods and  [Puppet `auth.conf`][] format and warns you that this is deprecated. If `false`, use the new authorization method and HOCON `auth.conf` format. Default: `true`.
* `authorization-required` and `client-whitelist` in the `puppet-admin` section: If either of these parameters has a non-empty value, Puppet Server authorizes requests to Puppet Server's administrative API per the parameters' values. See the [`puppetserver.conf` documentation][`puppetserver.conf`] for more information on these settings. If neither the `authorization-required` nor `client-whitelist` are specified, or if `client-whitelist` is specified but empty, Puppet Server authorizes requests using the new authorization method introduced in Puppet Server 2.2.
* `certificate-status.authorization-required` and `certificate-status.client-whitelist` in the `certificate-authority` section: If either of these parameters is present, Puppet Server handles requests made to its [Certificate Status](/puppet/latest/reference/http_api/http_certificate_status.html) API per the parameters' values. See the [`ca.conf` documentation](./config_file_ca.html) for more information on these settings. If neither parameter is specified, Puppet Server authorizes requests using the new authorization method introduced in Puppet Server 2.2.