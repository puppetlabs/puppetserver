---
layout: default
title: "Puppet Server Configuration Files: auth.conf"
canonical: "/puppetserver/latest/config_file_auth.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[Puppet `auth.conf`]: https://puppet.com/docs/puppet/latest/config_file_auth.html
[deprecated]: ./deprecated_features.markdown
[`puppetserver.conf`]: ./config_file_puppetserver.markdown

Puppet Server's `auth.conf` file contains rules for authorizing access to Puppet Server's HTTP API endpoints. For an overview, see [Puppet Server Configuration](./configuration.markdown).

The new Puppet Server authentication configuration and functionality is similar to the legacy method in that you define rules in a file named `auth.conf`, and Puppet Server applies the settings when a request's endpoint matches a rule.

However, Puppet Server now has its own `auth.conf` file that uses a new HOCON format with different parameters, syntax, and functionality. 

> **Note:** You can also use the [`puppetlabs-puppet_authorization`](https://forge.puppet.com/puppetlabs/puppet_authorization) module to manage the new `auth.conf` file's authorization rules in the new HOCON format, and the [`puppetlabs-hocon`](https://forge.puppet.com/puppetlabs/hocon) module to use Puppet to manage HOCON-formatted settings in general.

To configure how Puppet Server authenticates requests, use the supported HOCON `auth.conf` file and authorization methods, and see the parameters and rule definitions in the [HOCON Parameters](#hocon-parameters) section.

You can find the Puppet Server auth.conf file [here](https://github.com/puppetlabs/puppetserver/blob/master/ezbake/config/conf.d/auth.conf).


## HOCON example

Here is an example authorization section using the HOCON configuration format:

``` hocon
authorization: {
    version: 1
    rules: [
        {
            match-request: {
                path: "^/my_path/([^/]+)$"
                type: regex
                method: get
            }
            allow: [ node1, node2, node3, {extensions:{ext_shortname1: value1, ext_shortname2: value2}} ]
            sort-order: 1
            name: "user-specific my_path"
        },
        {
            match-request: {
                path: "/my_other_path"
                type: path
            }
            allow-unauthenticated: true
            sort-order: 2
            name: "my_other_path"
        },
    ]
}
```

For a more detailed example of how to use the HOCON configuration format, see [Configuring The Authorization Service](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md). 

For descriptions of each setting, see the following sections.

## HOCON parameters

Use the following parameters when writing or migrating custom authorization rules using the new HOCON format.

### `version`

The `version` parameter is required. In this initial release, the only supported value is `1`.

### `allow-header-cert-info`

> **Note:** Puppet Server ignores the setting of the same name in [`master.conf`](./config_file_master.markdown) in favor of this setting in the new `auth.conf` file. If you use the [deprecated][] authentication method and [Puppet `auth.conf`][] rules, you must instead configure this setting in `master.conf`.

This optional `authorization` section parameter determines whether to enable [external SSL termination](./external_ssl_termination.markdown) on all HTTP endpoints that Puppet Server handles, including those served by the "master" service, the certificate authority API, and the Puppet Admin API. It also controls how Puppet Server derives the user's identity for authorization purposes. The default value is `false`.

If this setting is `true`, Puppet Server ignores any presented certificate and relies completely on header data to authorize requests.

> **Warning!** This is very insecure; **do not enable this parameter** unless you've secured your network to prevent **any** untrusted access to Puppet Server.

You cannot rename any of the `X-Client` headers when this setting is enabled, and you must specify identity through the `X-Client-Verify`, `X-Client-DN`, and `X-Client-Cert` headers.

For more information, see [External SSL Termination](./external_ssl_termination.markdown#disable-https-for-puppet-server) in the Puppet Server documentation and [Configuring the Authorization Service](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md#allow-header-cert-info) in the `trapperkeeper-authorization` documentation.

### `rules`

The required `rules` array of a Puppet Server's HOCON `auth.conf` file determines how Puppet Server responds to a request. Each element is a map of settings pertaining to a rule, and when Puppet Server receives a request, it evaluates that request against each rule looking for a match.

You define each rule by adding parameters to the rule's [`match-request`](#match-request) section. A `rules` array can contain as many rules as you need, each with a single `match-request` section.

If a request matches a rule in a `match-request` section, Puppet Server determines whether to allow or deny the request using the `rules` parameters that follow the rule's `match-request` section:

-   At least one of:
    -   [`allow`](#allow-allow-unauthenticated-and-deny)
    -   [`allow-unauthenticated`](#allow-allow-unauthenticated-and-deny)
    -   [`deny`](#allow-allow-unauthenticated-and-deny)
-   [`sort-order`](#sort-order) (required)
-   [`name`](#name) (required)

If no rule matches, Puppet Server denies the request by default and returns an HTTP 403/Forbidden response.

#### `match-request`

A `match-request` can take the following parameters, some of which are required:

-   **`path` and `type` (required):** A `match-request` rule must have a `path` parameter, which returns a match when a request's endpoint URL starts with or contains the `path` parameter's value. The parameter can be a literal string or regular expression as defined in the required `type` parameter.

    ``` hocon
    # Regular expression to match a path in a URL.
    path: "^/puppet/v3/report/([^/]+)$"
    type: regex

    # Literal string to match the start of a URL's path.
    path: "/puppet/v3/report/"
    type: path
    ```

    > **Note:** While the HOCON format doesn't require you to wrap all string values with double quotation marks, some special characters commonly used in regular expressions --- such as `*` --- break HOCON parsing unless the entire value is enclosed in double quotes.

-   **`method`:** If a rule contains the optional `method` parameter, Puppet Server applies that rule only to requests that use its value's listed HTTP methods. This parameter's valid values are `get`, `post`, `put`, `delete`, and `head`, provided either as a single value or array of values.

    ``` hocon
    # Use GET and POST.
    method: [get, post]

    # Use PUT.
    method: put
    ```

    > **Note:** While the new HOCON format does not provide a direct equivalent to the [deprecated][] `method` parameter's `search` indirector, you can create the equivalent rule by passing GET and POST to `method` and specifying endpoint paths using the `path` parameter.

-   **`query-params` and `environment`:** Use the optional `query-params` setting and provide the list of query parameters as an array to the setting's `environment` parameter.

    For example, this rule would match a request URL containing the `environment=production` or `environment=test` query parameters:

    ``` hocon
    query-params: {
        environment: [ production, test ]
    }
    ```

#### `allow`, `allow-unauthenticated`, and `deny`

After each rule's `match-request` section, it must also have an `allow`, `allow-unauthenticated`, or `deny` parameter. (You can set both `allow` and `deny` parameters for a rule, though Puppet Server always prioritizes `deny` over `allow` when a request matches both.)

If a request matches the rule, Puppet Server checks the request's authenticated "name" (see [`allow-header-cert-info`](#allow-header-cert-info)) against these parameters to determine what to do with the request.

-   **`allow-unauthenticated`**: If this Boolean parameter is set to `true`, Puppet Server allows the request --- even if it can't determine an authenticated name. **This is a potentially insecure configuration** --- be careful when enabling it. A rule with this parameter set to `true` can't also contain the `allow` or `deny` parameters.
-   **`allow`**: This parameter can take a single string value, an array of string values, a single map value with either an `extensions` or `certname` key, or an array of string and map values.

    The string values can contain:

    -   An exact domain name, such as `www.example.com`.
    -   A glob of names containing a `*` in the first segment, such as `*.example.com` or simply `*`.
    -   A regular expression surrounded by `/` characters, such as `/example/`.
    -   A backreference to a regular expression's capture group in the `path` value, if the rule also contains a `type` value of `regex`. For example, if the path for the rule were `"^/example/([^/]+)$"`, you can make a backreference to the first capture group using a value like `$1.domain.org`.

    The map values can contain:

    -   An `extensions` key that specifies an array of matching X.509 extensions. Puppet Server authenticates the request only if each key in the map appears in the request, and each key's value exactly matches.
    -   A `certname` key equivalent to a bare string.

    If the request's authenticated name matches the parameter's value, Puppet Server allows it.
-   **`deny`**: This parameter can take the same types of values as the `allow` parameter, but refuses the request if the authenticated name matches --- even if the rule contains an `allow` value that also matches.

>
> Also, in the HOCON Puppet Server authentication method, there is no directly equivalent behavior to the [deprecated][] `auth` parameter's `on` value.

#### `sort-order`

After each rule's `match-request` section, the required `sort-order` parameter sets the order in which Puppet Server evaluates the rule by prioritizing it on a numeric value between 1 and 399 (to be evaluated before default Puppet rules) or 601 to 998 (to be evaluated after Puppet), with lower-numbered values evaluated first. Puppet Server secondarily sorts rules lexicographically by the `name` string value's Unicode code points.

``` hocon
sort-order: 1
```

#### `name`

After each rule's `match-request` section, this required parameter's unique string value identifies the rule to Puppet Server. The `name` value is also written to server logs and error responses returned to unauthorized clients.

``` hocon
name: "my path"
```

> **Note:** If multiple rules have the same `name` value, Puppet Server will fail to launch.