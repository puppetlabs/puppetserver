---
layout: default
title: "Puppet Server: Puppet API: Environment Modules"
canonical: "/puppetserver/latest/puppet-api/v3/environment_modules.html"
---

[deprecated WEBrick Puppet master]: https://docs.puppet.com/puppet/latest/reference/services_master_webrick.html
[`auth.conf`]: ../../config_file_auth.markdown
[`puppetserver.conf`]: ../../config_file_puppetserver.markdown

The environment modules API will return information about what modules are
installed for the requested environment.

This endpoint is available only when the Puppet master is running Puppet Server, not
on Ruby Puppet masters, such as the [deprecated WEBrick Puppet master][]. It also ignores
the Ruby-based Puppet master's authorization methods and configuration. See the
[Authorization section](#authorization) for more information.

## `GET /puppet/v3/environment_modules?environment=:environment`

Making a request with no query parameters is not supported and returns an HTTP 400 (Bad
Request) response.

### Supported HTTP Methods

GET

### Supported Formats

JSON

### Query Parameters

Provide one parameter to the GET request:

* `environment`: Request information about modules pertaining to the specified
environment only.

### Responses

#### GET request with results

```
GET /puppet/v3/environment_modules?environment=env

HTTP/1.1 200 OK
Content-Type: application/json

{
    "modules": [
        {
            "name": "puppetlabs/ntp",
            "version": "6.0.0"
        },
        {
            "name": "puppetlabs/stdlib",
            "version": "4.14.0"
        }
    ],
    "name": "env"
}
```

#### Environment does not exist

If you send a request with an environment parameter that doesn't correspond to the name of a
directory environment on the server, the server returns an HTTP 404 (Not Found) error:

```
GET /puppet/v3/environment_modules?environment=doesnotexist

HTTP/1.1 404 Not Found

Could not find environment 'doesnotexist'
```

#### No environment given

```
GET /puppet/v3/environment_modules

HTTP/1.1 400 Bad Request

An environment parameter must be specified
```

#### Environment parameter specified with no value

```
GET /puppet/v3/environment_modules?environment=

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not ''
```

#### Environment includes non-alphanumeric characters

If the environment parameter in your request includes any characters that are
not `A-Z`, `a-z`, `0-9`, or `_` (underscore), the server returns an HTTP 400 (Bad Request) error:

```
GET /puppet/v3/environment_modules?environment=bog|us

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not 'bog|us'
```

### Schema

An environment modules response body conforms to the
[environment modules schema](./environment_modules.json).

### Authorization

Unlike other Puppet master service-based API endpoints, the environment modules API is
provided exclusively by Puppet Server. All requests made to the environment
modules API are authorized using the Trapperkeeper-based [`auth.conf`][] feature
introduced in Puppet Server 2.2.0, and ignores the older Ruby-based authorization process
and configuration. The value of the `use-legacy-auth-conf` setting in the `jruby-puppet`
configuration section of [`puppetserver.conf`][] is ignored for requests
to the environment modules API, because the Ruby-based authorization process is not equipped to
authorize these requests.

For more information about the Puppet Server authorization process and configuration
settings, see the [`auth.conf` documentation][`auth.conf`].
