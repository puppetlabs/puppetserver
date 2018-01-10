---
layout: default
title: "Puppet Server: Puppet API: Environment Modules"
canonical: "/puppetserver/latest/puppet-api/v3/environment_modules.html"
---

[deprecated WEBrick Puppet master]: https://puppet.com/docs/puppet/latest/services_master_webrick.html
[`auth.conf`]: ../../config_file_auth.markdown
[`puppetserver.conf`]: ../../config_file_puppetserver.markdown

The environment modules API will return information about what modules are
installed for the requested environment.

This endpoint is available only when the Puppet master is running Puppet Server, not
on Ruby Puppet masters, such as the [deprecated WEBrick Puppet master][]. It also ignores
the Ruby-based Puppet master's authorization methods and configuration. See the
[Authorization section](#authorization) for more information.

## `GET /puppet/v3/environment_modules`

### Supported HTTP Methods

GET

### Supported Formats

JSON

### Responses

#### GET request with results

```
GET /puppet/v3/environment_modules

HTTP/1.1 200 OK
Content-Type: application/json

[{
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
},
{
    "modules": [
        {
            "name": "puppetlabs/stdlib",
            "version": "4.14.0"
        },
        {
            "name": "puppetlabs/azure",
            "version": "1.1.0"
        }
    ],
    "name": "production"
}]
```

## `GET /puppet/v3/environment_modules?environment=:environment`

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

### No metadata.json file

If your modules do not have a [metadata.json](https://puppet.com/docs/puppet/latest/modules_metadata.html)
file, puppetserver will not be able to determine the version of your module. In
this case, puppetserver will return a null value for `version` in the response
body.

### Schema

An environment modules response body conforms to the
[environment modules schema](./environment_modules.json).

#### Validating your json

If you have a response body that you'd like to validate against the
[environment_modules.json](./environment_modules.json) schema, you can do so using the ruby library
[json-schema](https://github.com/ruby-json-schema/json-schema).

First, install the ruby gem to be used:

```bash
gem install json-schema
```

Next, given a json file, you can validate its schema.

Here is a basic json file called _example.json_:

```json
{
    "modules": [
        {
            "name": "puppetlabs/ntp",
            "version": "6.0.0"
        },
        {
            "name": "puppetlabs/stdlib",
            "version": "4.16.0"
        }
    ],
    "name": "production"
}
```

Run this command from the root dir of the puppetserver project (or update the
path to the json schema file in the command below):

```bash
ruby -rjson-schema -e "puts JSON::Validator.validate!('./documentation/puppet-api/v3/environment_modules.json','example.json')"
```

If the json is a valid schema, the command should output `true`. Otherwise, the
library will print a schema validation error detailing which key or keys validate
the schema.

If you have a response that is the entire list of environment modules (i.e. the
environment_modules endpoint), you will need to use this command to validate
the json schema:

```bash
ruby -rjson-schema -e "puts JSON::Validator.validate!('./documentation/puppet-api/v3/environment_modules.json','all.json', :list=>true)"
```

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
