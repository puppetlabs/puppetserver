---
layout: default
title: "Puppet Server Configuration Files: Migrating to the HOCON auth.conf Format"
canonical: "/puppetserver/latest/config_file_auth_migration.html"
---

[Puppet `auth.conf`]: https://puppet.com/docs/puppet/latest/config_file_auth.markdown
[deprecated]: ./deprecated_features.markdown
[Backward Compatibility with Puppet 3 Agents]: ./compatibility_with_puppet_agent.markdown

Puppet Server 2.2.0 introduced a significant change in how it manages authentication to API endpoints. The older [Puppet `auth.conf`][] file and whitelist-based authorization method are [deprecated][]. Puppet Server's new `auth.conf` file, illustrated below in examples, also uses a different format for authorization rules.

Use the following examples and methods to convert your authorization rules when upgrading to Puppet Server 2.2.0 and newer. For detailed information about using the new or deprecated `auth.conf` rules with Puppet Server, see the [Puppet Server `auth.conf` documentation](./config_file_auth.markdown).

> **Note:** To continue using the deprecated [Puppet `auth.conf`][] file and authorization rule format, see the [Deprecated Ruby Parameters](https://puppet.com/docs/puppet/latest/config_file_auth.markdown#deprecated-ruby-parameters) section of the `auth.conf` documentation. To support both Puppet 3 and Puppet 4 agents connecting to Puppet Server, see [Backward Compatibility with Puppet 3 Agents][].

## Managing rules with Puppet modules

You can reimplement and manage your authorization rules in the new HOCON format and `auth.conf` file by using the [`puppetlabs-puppet_authorization`](https://forge.puppet.com/puppetlabs/puppet_authorization) Puppet module. See the module's documentation for details.

## Converting rules directly

Most of the deprecated authorization rules and settings are available in the new format.

### Unavailable rules, settings, or values

The following rules, settings, and values have no direct equivalent in the new HOCON format. If you require them, you must reimplement them differently in the new format.

* **on value of `auth`:** The deprecated `auth` parameter's on value results in a match only when a request provides a client certificate. There is no equivalent behavior in the HOCON format.
* **`allow_ip` or `deny_ip` parameters**
* **`method` parameter's search indirector:** While there is no direct equivalent to the deprecated search indirector, you can create an equivalent HOCON rule. See [below](#search-indirector-for-method) for an example.

> **Note:** Puppet Server considers the state of a request's authentication differently depending on whether the authorization rules use the older Puppet `auth.conf` or newer HOCON formats. An authorization rule that uses the deprecated format evaluates the `auth` parameter as part of rule-matching process. A HOCON authorization rule first determines whether the request matches other parameters of the rule, and then considers the request's authentication state (using the rule's `allow`, `deny`, or `allow-authenticated` values) after a successful match only.

### Basic HOCON structure

The HOCON `auth.conf` file has some fundamental structural requirements:

* An [`authorization`](./config_file_auth.markdown#authorization) section, which contains:
  * A [`version`](./config_file_auth.markdown#version) setting.
  * A [`rules`](./config_file_auth.markdown#rules) array of map values, each representing an authorization rule. Each rule must contain:
      * A [`match-request`](./config_file_auth.markdown#match-request) section.
          * Each `match-request` section must contain at least one [`path`](./config_file_auth.markdown#path) and [`type`](./config_file_auth.markdown#type).
      * A numeric [`sort-order`](./config_file_auth.markdown#sort-order) value.
          * If the value is between 1 and 399, the rule supersedes Puppet Server's default authorization rules.
          * If the value is between 601 and 998, the rule can be overridden by Puppet Server's default authorization rules.
      * A string [`name`](./config_file_auth.markdown#name) value.
      * At least one of the following:
          * An [`allow` value, a `deny` value, or both](./config_file_auth.markdown#allow-allow-unauthenticated-and-deny). The `allow` or `deny` values can contain:
              * A single string, representing the request's "name" derived from the Common Name (CN) attribute within an X.509 certificate's Subject Distinguished Name (DN). This string can be an exact name, a glob, or a regular expression.
              * A single map value containing an `extension` key.
              * A single map value containing a `certname` key.
              * An array of values, including string and map values.
          * An [`allow-unauthenticated`](./config_file_auth.markdown#allow-allow-unauthenticated-and-deny) value, but if present, there cannot also be an `allow` value.

For an full example of a HOCON `auth.conf` file, see the [HOCON `auth.conf` documentation](./config_file_auth.markdown#hocon-example).

### Converting a simple rule

Let's convert this simple deprecated `auth.conf` authorization rule:

```
path /puppet/v3/environments
method find
allow *
```

We'll start with a skeletal, incomplete HOCON `auth.conf` file:

``` hocon
authorization: {
    version: 1
    rules: [
        {
            match-request: {
                path:
                type:
            }
            allow:
            sort-order: 1
            name:
        },
    ]
}
```

Next, let's convert each component of the deprecated rule to the new HOCON format.

1.  Add the path to the new rule's [`path`](./config_file_auth.markdown#match-request) setting in its `match-request` section.

    ``` hocon
    ...
            match-request: {
                path: /puppet/v3/environments
                type:
            }
            allow:
            sort-order: 1
            name:
        },
    ...
    ```

2.  Next, add its type to the section's [`type`](./config_file_auth.markdown#match-request) setting. Since this is a literal string path, the type is `path`.

    ``` hocon
    ...
            match-request: {
                path: /puppet/v3/environments
                type: path
            }
            allow:
            sort-order: 1
            name:
        },
    ...
    ```

3.  The legacy rule has a [`method`](./config_file_auth.markdown#method-1) setting, with an indirector value of `find` that's equivalent to the GET and POST HTTP methods. We can implement these by adding an optional HOCON [`method`](./config_file_auth.markdown#match-request) setting in the rule's `match-request` section and specifying GET and POST as an array.

    ``` hocon
    ...
            match-request: {
                path: /puppet/v3/environments
                type: path
                method: [get, post]
            }
            allow:
            sort-order: 1
            name:
        },
    ...
    ```

4.  Next, set the [`allow`](#allow-allow-unauthenticated-and-deny) setting. The legacy rule used a `*` glob, which is also supported in HOCON.

    ``` hocon
    ...
            match-request: {
                path: /puppet/v3/environments
                type: path
                method: [get, post]
            }
            allow: "*"
            sort-order: 1
            name:
        },
    ...
    ```

5.  Finally, give the rule a unique [`name`](#name) value. Remember that the rule will appear in logs and in the body of error responses to unauthorized clients.

    ``` hocon
    ...
            match-request: {
                path: /puppet/v3/environments
                type: path
                method: [get, post]
            }
            allow: "*"
            sort-order: 1
            name: "environments"
        },
    ...
    ```

Our HOCON `auth.conf` file should now allow all authenticated clients to make GET and POST requests to the `/puppet/v3/environments` endpoint, and should look like this:

``` hocon
authorization: {
    version: 1
    rules: [
        {
            match-request: {
                path: /puppet/v3/environments
                type: path
                method: [get, post]
            }
            allow: "*"
            sort-order: 1
            name: "environments"
        },
    ]
}
```

### Converting more complex rules

#### Paths set by regular expressions

To convert a regular expression path, enclose it in double quotation marks and slash characters (`/`), and set the `type` to regex.

> **Note:** You must escape regular expressions to conform to HOCON standards, which are the same as JSON's and differ from the deprecated format's regular expressions. For instance, the digit-matching regular expression `\d` must be escaped with a second backslash, as `\\d`.

Deprecated:

```
path ~ ^/puppet/v3/catalog/([^/]+)$
```

HOCON:

``` hocon
authorization: {
    version: 1
    rules: [
        {
        match-request: {
            path: "^/puppet/v3/catalog/([^/]+)$"
            type: regex
...
```

> **Note:** You must escape regular expressions to conform to HOCON standards, which are the same as JSON's and differ from the deprecated format's regular expressions. For instance, the digit-matching regular expression `\d` must be escaped with a second backslash, as `\\d`.

Backreferencing works the same way it does in the deprecated format.

Deprecated:

```
path ~ ^/puppet/v3/catalog/([^/]+)$
allow $1
```

HOCON:

``` hocon
authorization: {
    version: 1
    rules: [
        {
        match-request: {
            path: "^/puppet/v3/catalog/([^/]+)$"
            type: regex
        }
        allow: "$1"
...
```

#### Allowing unauthenticated requests

To have a rule match any request regardless of its authentication state, including unauthenticated requests, a deprecated rule would assign the any value to the `auth` parameter. In a HOCON rule, set the `allow-unauthenticated` parameter to true. This overrides the `allow` and `deny` parameters and **is an insecure configuration** that should be used with caution.

Deprecated:

```
auth: any
```

HOCON:

``` hocon
authorization: {
    version: 1
    rules: [
        {
        match-request: {
            ...
        }
        allow-unauthenticated: true
...
```

#### Multiple `method` indirectors

If a deprecated rule has multiple `method` indirectors, combine all of the related HTTP methods to the HOCON `method` array.

Deprecated:

```
method find, save
```

The deprecated find indirector corresponds to the GET and POST methods, and the save indirector corresponds to the PUT method. In the HOCON format, simply combine these methods in an array.

HOCON:

``` hocon
authorization: {
    version: 1
    rules: [
        {
        match-request: {
            ...
            method: [get, post, put]
        }
...
```

#### Environment URL parameters

In deprecated rules, the `environment` parameter adds a comma-separated list of query parameters as a suffix to the base URL. HOCON rules allow you to pass them as an array `environment` value inside the `query-params` setting. Rules in both the deprecated and HOCON formats match *any* `environment` value.

Deprecated:

```
environment: production,test
```

HOCON:

``` hocon
authorization: {
    version: 1
    rules: [
        {
        match-request: {
            ...
            query-params: {
                environment: [ production, test ]
            }
        }
...
```

> **Note:** The `query-params` approach above replaces environment-specific rules for both Puppet 3 and Puppet 4. If you're supporting agents running both Puppet 3 and Puppet 4, see [Backward Compatibility with Puppet 3 Agents][] for more information.

#### Search indirector for `method`

There's no direct equivalent to the search indirector for the deprecated `method` setting. Create the equivalent rule by passing GET and POST to `method` and specifying endpoint paths using the `path` parameter.

Deprecated:

```
path ~ ^/puppet/v3/file_metadata/user_files/
method search
```

HOCON:

``` hocon
authorization: {
    version: 1
    rules: [
        {
        match-request: {
            path: "^/puppet/v3/file_metadatas?/user_files/"
            type: regex
            method: [get, post]
        }
...
```
