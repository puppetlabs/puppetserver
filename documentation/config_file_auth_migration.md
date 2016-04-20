---
layout: default
title: "Puppet Server Configuration Files: Migrating to the HOCON auth.conf format"
canonical: "/puppetserver/latest/config_file_auth_migration.html"
---

[Puppet `auth.conf`]: /puppet/latest/reference/config_file_auth.html
[deprecated]: ./deprecated_features.html

Puppet Server 2.2.0 introduces a significant change in how it manages authentication to API endpoints. The older [Puppet `auth.conf`][] file and whitelist-based authorization method are [deprecated][]. Puppet Server's new `auth.conf` file, illustrated below in examples, also uses a different format for authorization rules.

Use the following examples and methods to convert your authorization rules when upgrading from Puppet Server 2.2.0 and newer. For detailed information about using the new or deprecated `auth.conf` rules with Puppet Server, see the [Puppet Server `auth.conf` documentation](./config_file_auth.html).

> **Note:** To continue using the deprecated Ruby [Puppet `auth.conf`][] file and authorization methods, see the [Deprecated Ruby Parameters](/puppet/latest/reference/config_file_auth.html#deprecated-ruby-parameters) section of the `auth.conf` documentation.

## Managing rules with Puppet modules

You can reimplement and manage your authorization rules in the new HOCON format and `auth.conf` file by using the [`puppetlabs-puppet_authorization`](https://forge.puppet.com/puppetlabs/puppet_authorization) Puppet module. See the module's documentation for details.

## Converting rules directly

Most of the deprecated authorization rules and settings are available in the new format.

> ### Unavailable rules, settings, or values
>
> The following rules, settings, and values have no direct equivalent in the new HOCON format. If you require them, you must reimplement them differently in the new format.
>
> * **on value of `auth`:** The deprecated `auth` parameter's on value results in a match only when a request provides a client certificate. There is no equivalent behavior in the HOCON format.
> * **`allow_ip` or `deny_ip` parameters**
> * **`method` parameter's search indirector:** While there is no direct equivalent to the deprecated search indirector, you can create an equivalent HOCON rule. See [below](#search-indirector-for-method) for an example.

### Basic HOCON structure

The HOCON `auth.conf` file has some fundamental structural requirements:

* An `authorization` section, which contains:
  * A `version` setting.
  * A `rules` array of map values, each representing an authorization rule. Each rule must contain:
      * A `match-request` section.
          * Each `match-request` section must contain at least one `path` and `type`.
      * A numeric `sort-order` value.
      * A string `name` value.
      * At least one of the following:
          * An `allow` value, a `deny` value, or both. The `allow` or `deny` values can contain:
              * A single string, representing the request's "name" derived from the Common Name (CN) attribute within an X.509 certificate's Subject Distinguished Name (DN). This string can be an exact name, a glob, or a regular expression.
              * A single map value containing an `extension` key.
              * A single map value containing a `certname` key.
              * An array of values, including string and map values.
          * An `allow-unauthenticated` value, but if present, there cannot also be an `allow` value.

For an full example, see the [HOCON `auth.conf` documentation](./config_file_auth.html#hocon-example).

### Converting a simple rule

Let's convert this simple deprecated `auth.conf` authorization rule:

```
path /puppet/v3/environments
method find
allow *
```

We'll start with a skeletal HOCON `auth.conf` file:

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

Next, let's convert each component of the deprecated rule in the new HOCON format.

1. Add the path to the new rule's [`path`](./config_file_auth.html#match-request) setting in its `match-request` section.

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

2. Next, add its type to the section's [`type`](./config_file_auth.html#match-request) setting. Since this is a literal string path, the type is `path`.

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

3. The legacy rule has a [`method`](./config_file_auth.html#method-1) setting, with an indirector value of `find` that's equivalent to the GET and POST HTTP methods. We can implement these by adding an optional HOCON [`method`](./config_file_auth.html#match-request) setting in the rule's `match-request` section and specifying GET and POST as an array.

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

4. Next, set the [`allow`](#allow-allow-unauthenticated-and-deny) setting. The legacy rule used a `*` glob, which is also supported in HOCON.

    ``` hocon
    ...
            match-request: {
                path: /puppet/v3/environments
                type: path
                method: [get, post]
            }
            allow: *
            sort-order: 1
            name:
        },
    ...
    ```

5. Finally, give the rule a unique [`name`](#name) value. Remember that the rule will appear in logs and in the body of error responses to unauthorized clients.

    ``` hocon
    ...
            match-request: {
                path: /puppet/v3/environments
                type: path
                method: [get, post]
            }
            allow: *
            sort-order: 1
            name: "environments"
        },
    ...
    ```

Our HOCON `auth.conf` file should now allow all clients to GET and POST to the `/puppet/v3/environments` endpoint, and should look like this:

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
            allow: *
            sort-order: 1
            name: "environments"
        },
    ]
}
```

### Converting more complex rules

#### Paths set by regular expressions

To convert a regular expression path, enclose it in double quotation marks and solidus characters (`/`), and set the `type` to regex.

> **Note:** You must escape regular expressions to conform to HOCON standards, which are the same as JSON's and differ from the deprecated format's regular expressions. For instance, the digit-matching regular expression `\d` must be escaped with a second backslash, as `\\d`.

Deprecated:

```
path ~ ^/puppet/v3/catalog/([^/]+)$
```

HOCON:

``` hocon
...
        match-request: {
            path: "/^\/puppet\/v3\/catalog\/([^\/]+)$/"
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
...
        match-request: {
            path: "/^\/puppet\/v3\/catalog\/([^\/]+)$/"
            type: regex
        }
        allow: $1
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
...
        match-request: {
            ...
            method: [get, post, put]
        }
```

#### Search indirector for `method`

There's no direct equivalent to the search indirector for the deprecated `method` setting. Create the equivalent rule by passing GET and POST to `method` and specifying endpoint paths using the `path` parameter.

Deprecated:

```

``` hocon
...
        match-request: {
            path: "/(s|_search)$/"
            type: regex
            method: [get, post]
        }
...
```