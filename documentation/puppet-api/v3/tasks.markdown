---
layout: default
title: "Puppet Server: Puppet API: Tasks"
canonical: "/puppetserver/latest/puppet-api/v3/tasks.html"
---

[deprecated WEBrick Puppet master]: https://puppet.com/docs/puppet/latest/services_master_webrick.html
[`environment_timeout`]: https://puppet.com/docs/puppet/latest/config_file_environment.html#environmenttimeout

[`auth.conf`]: ../../config_file_auth.markdown
[`puppetserver.conf`]: ../../config_file_puppetserver.markdown

The tasks API provides access to task information stored in modules. Tasks are
files stored in `tasks` subdirectory of a module. A task consists of an
executable file, with an optional metadata file with the same name with an
added '.json' extension. For example, the "install" task in a module "apache" could
consist of the executable file `install.rb` and the metadata file
`install.json`. This task would have the display name "apache::install".

This endpoint is available only when the Puppet master is running Puppet Server, not
on Ruby Puppet masters, such as the [deprecated WEBrick Puppet master][]. It also ignores
the Ruby-based Puppet master's authorization methods and configuration. See the
[Authorization section](#authorization) for more information.

> Note: Tasks file contents in versioned code can be retrieved using the [`static_file_content`](./static_file_content.markdown) endpoint.

### Does not return entries for task files with invalid names

A task file name has the same restriction as Puppet type names and must match
the regular expression `\A[a-z][a-z0-9_]*\z` (excluding extensions).

### Returns entries for tasks with no executable files

A task will be listed if only metadata for it exists. How many files are
associated with a task can be found by querying that task's details.

### Does not read files

This endpoint will not parse metadata or read any other files, only file names.

### Uses `application/json` Content-Type

The Content-Type in the response to an task API query is
`application/json`.

## `GET /puppet/v3/tasks?environment=:environment`

(Introduced in Puppet Server 5.1.0.)

Making a request with no query parameters is not supported and returns an HTTP 400 (Bad
Request) response.

### Supported HTTP Methods

GET

### Supported Formats

JSON

### Query Parameters

Provide one parameter to the GET request:

* `environment`: Only the task information pertaining to the specified
environment will be returned for the call.

### Responses

#### GET request with results

```
GET /puppet/v3/tasks?environment=env

HTTP/1.1 200 OK
Etag: b02ede6ecc432b134217a1cc681c406288ef9224
Content-Type: application/json

[
  {
    "name":"apache::install",
    "environment":[
      {
        "name":"env",
        "code_id":null
      }
    ]
  },
  {
    "name":"dashboard::configure",
    "environment":[
      {
        "name":"env",
        "code_id":null
      }
    ]
  }
]
```

#### Environment does not exist

If you send a request with an environment parameter that doesn't correspond to the name of a
directory environment on the server, the server returns an HTTP 404 (Not Found) error:

```
GET /puppet/v3/tasks?environment=doesnotexist

HTTP/1.1 404 Not Found

Could not find environment 'doesnotexist'
```

#### No environment given

```
GET /puppet/v3/tasks

HTTP/1.1 400 Bad Request

You must specify an environment parameter.
```

#### Environment parameter specified with no value

```
GET /puppet/v3/tasks?environment=

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not ''
```

#### Environment includes non-alphanumeric characters

If the environment parameter in your request includes any characters that are
not `A-Z`, `a-z`, `0-9`, or `_` (underscore), the server returns an HTTP 400 (Bad Request) error:

```
GET /puppet/v3/tasks?environment=bog|us

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not 'bog|us'
```

### Schema

A tasks response body conforms to the [tasks schema](./tasks.json).

### Authorization

Unlike other Puppet master service-based API endpoints, the tasks API is
provided exclusively by Puppet Server. All requests made to the tasks API are
authorized using the Trapperkeeper-based [`auth.conf`][] feature introduced in
Puppet Server 2.2.0, and ignores the older Ruby-based authorization process and
configuration. The value of the `use-legacy-auth-conf` setting in the
`jruby-puppet` configuration section of [`puppetserver.conf`][] is ignored for
requests to the tasks API, because the Ruby-based authorization process is not
equipped to authorize these requests.

For more information about the Puppet Server authorization process and configuration
settings, see the [`auth.conf` documentation][`auth.conf`].
