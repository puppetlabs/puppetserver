---
layout: default
title: "Puppet Server: Puppet API: Static File Content"
canonical: "/puppetserver/latest/puppet-api/v3/static_file_content.html"
---

[`code-content-command`]: https://docs.puppetlabs.com/puppetserver/latest/config_file_puppetserver.html
[static catalog]: https://docs.puppetlabs.com/puppet/latest/reference/static_catalogs.html
[catalog]: https://docs.puppetlabs.com/puppet/latest/reference/subsystem_catalog_compilation.html
[file resource]: https://docs.puppetlabs.com/puppet/latest/reference/type.html#file
[environment]: https://docs.puppetlabs.com/puppet/latest/reference/environments.html
[auth.conf]: https://docs.puppetlabs.com/puppetserver/latest/config_file_auth.html

The `static_file_content` endpoint returns the standard output of a
[`code-content-command`][] script, which should output the contents of a specific version
of a [file resource][] that has a `source` attribute with a `puppet:///` URI value. That
source must be a file from the `files` directory of a module in a specific [environment][].

Puppet Agent uses this endpoint only when applying a [static catalog][], and this endpoint
is available only when the Puppet master is running Puppet Server. This endpoint does not
exist on Ruby Puppet masters, such as the
[deprecated WEBrick Puppet master](https://docs.puppetlabs.com/puppet/latest/reference/services_master_webrick.html).

## `GET /puppet/v3/static_file_content/<FILE-PATH>`

(Introduced in Puppet Server 2.3.0)

To retrieve a specific version of a file at a given environment and path, make an HTTP
request to this endpoint with the required parameters. The `<FILE-PATH>` segment of the
endpoint corresponds to the requested file's path, relative to the given environment's
root directory, and is required.

### Query parameters

You must also pass two parameters in the GET request:

-   `code_id`: a unique string provided by the [catalog][] that identifies which version
    of the file to return.
-   `environment`: the environment that contains the desired file.

### Response

A successful request to this endpoint returns an `HTTP 200` response code, and the
contents of the specified file's requested version in the response body.

-   400: Error; returned when any of the parameters are not provided.
-   403: Error; returned when requesting a file that is not within a module's `files`
directory.
-   500: Error; returned when `code-content-command` is not configured on the server, or
when a requested file or version is not present in a repository.

#### Example response

On a server at `localhost`, assume a versioned file is located at
`/modules/example/files/data.txt` in the `production` environment. The version is
identified by a `code_id` of
`urn:puppet:code-id:1:67eb71417fbd736a619c8b5f9bfc0056ea8c53ca;production`, and that version of the file contains `Puppet test`.

Given this command:

```
curl -i -k 'https://localhost:8140/puppet/v3/static_file_content/modules/example/files/data.txt?code_id=urn:puppet:code-id:1:67eb71417fbd736a619c8b5f9bfc0056ea8c53ca;production&environment=production'
```

Puppet Server should return:

```
HTTP/1.1 200 OK
Date: Wed, 2 Mar 2016 23:44:08 GMT
X-Puppet-Version: 4.4.0
Content-Length: 4
Server: Jetty(9.2.10.v20150310)

Puppet test
```

### Notes

When requesting a file from this endpoint, Puppet Server passes the values of the
`file-path`, `code_id`, and `environment` parameters as arguments to the
`code-content-command` script. If the script returns an exit code of 0, Puppet Server
returns the script's standard output, which should be the contents of the requested
version of the file.

This endpoint returns an error (status 500) if the [`code-content-command`][] setting
is not configured on Puppet Server.

#### Authorization

Puppet Server **always** authorizes requests made to the `static_file_content` API endpoint
with the [Trapperkeeper-based `auth.conf` feature][auth.conf] introduced in Puppet Server
2.2. This is different than most other Puppet master service-based endpoints, for which
the authorization mechanism is controlled by the `use-legacy-auth-conf` setting in the
`jruby-puppet` configuration section. The value of the `use-legacy-auth-conf` setting is
ignored for the `static_file_content` API endpoint, and Puppet Server **never** uses the
legacy `auth.conf` mechanism when authorizing requests. For more information about
authorization options, see the [`auth.conf` documentation][auth.conf].
