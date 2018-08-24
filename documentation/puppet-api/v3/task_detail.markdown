---
layout: default
title: "Puppet Server: Puppet API: Task detail"
canonical: "/puppetserver/latest/puppet-api/v3/task-detail.html"
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

This endpoint, `/puppet/v3/tasks/:module/:taskname`, allows you to fetch the
details about a task: its metadata, if present, and its associated executable
files. The file entries have additional data on how to fetch their contents so
they can be downloaded and run.

This endpoint is available only when the Puppet master is running Puppet Server, not
on Ruby Puppet masters, such as the [deprecated WEBrick Puppet master][]. It also ignores
the Ruby-based Puppet master's authorization methods and configuration. See the
[Authorization section](#authorization) for more information.

> Note: Tasks file contents in versioned code can be retrieved using the [`static_file_content`](./static_file_content.markdown) endpoint.

### Does not return entries for task files with invalid names

A task file name has the same restriction as puppet type names and must match
the regular expression `\A[a-z][a-z0-9_]*\z` (excluding extensions).

### Will error if the tasks implementations are invalid

Since the returning file information requires parsing metadata and finding
implementation files this endpoint will error if the metadata cannot be parsed
or the implementation content is invalid.

### Does read files

This endpoint will read in contents of metadata and other task files, so it may
be more expensive than the `/tasks` endpoint.

### Uses `application/json` Content-Type

The Content-Type in the response to an task API query is
`application/json`.

## `GET /puppet/v3/tasks/:module/:task?environment=:environment`

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
GET /puppet/v3/tasks/module/taskname?environment=env

HTTP/1.1 200 OK
Etag: b02ede6ecc432b134217a1cc681c406288ef9224
Content-Type: application/json

{
  "metadata": {
    "description": "Install a package",
    "parameters": {
      "name": {
        "description": "The package to install",
        "type": "String[1]"
      }
    }
  },
  "files": [
    {"filename": "taskname.rb",
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "size_bytes": 1024,
      "uri:" {
        "path": "/puppet/v3/file_content/tasks/module/taskname.rb",
        "params": {
          "environment": "production"
        }
      }
    }
  ]
}
```

#### GET request for invalid module

If you request details for a task which cannot be computed because the metadata
is unreadable or it's implementations are not usable Bolt will return an error
response with a status code of 500 containing `kind`, `msg`, and `details` keys.

```
GET /puppet/v3/tasks/modulename/taskname?environment=env
HTTP/1.1 500 Server Error
Content-Type: application/json

{
    "details": {},
    "kind": "puppet.tasks/unparseable-metadata",
    "msg": "unexpected token at '{ \"name\": \"init.sh\" , }\n  ]\n}\n'"
}
```

#### Environment does not exist

If you send a request with an environment parameter that doesn't correspond to the name of a
directory environment on the server, the server returns an HTTP 404 (Not Found) error:

```
GET /puppet/v3/tasks/module/taskname?environment=doesnotexist

HTTP/1.1 404 Not Found

Could not find environment 'doesnotexist'
```

#### No environment given

```
GET /puppet/v3/tasks/module/taskname

HTTP/1.1 400 Bad Request

You must specify an environment parameter.
```

#### Environment parameter specified with no value

```
GET /puppet/v3/tasks/module/taskname?environment=

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not ''
```

#### Environment includes non-alphanumeric characters

If the environment parameter in your request includes any characters that are
not `A-Z`, `a-z`, `0-9`, or `_` (underscore), the server returns an HTTP 400 (Bad Request) error:

```
GET /puppet/v3/tasks/module/taskname?environment=bog|us

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not 'bog|us'
```

#### Module does not exist

If you send a request for a task in a module that doesn't correspond to the
name of a module on the server, the server returns an HTTP 404 (Not Found)
error:

```
GET /puppet/v3/tasks/doesnotexist/taskname?environment=env

HTTP/1.1 404 Not Found

Could not find module 'doesnotexist'
```

#### Task does not exist or does not have a valid name

If you send a request for a task in that doesn't correspond to the name of a
task on the server, but the module does exist, the server returns an HTTP 404
(Not Found) error:

```
GET /puppet/v3/tasks/module/doesnotexist?environment=env

HTTP/1.1 404 Not Found

Could not find task 'doesnotexist'
```

### Schema

A tasks detail response body conforms to the [task detail schema](./task_detail.json).

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
