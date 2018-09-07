---
layout: default
title: "Puppet Server: Puppet API: Static File Content"
canonical: "/puppetserver/latest/puppet-api/v3/static_file_content.html"
---

[`code-content-command`]: https://puppet.com/docs/puppetserver/latest/config_file_puppetserver.html
[static catalog]: https://puppet.com/docs/puppet/latest/static_catalogs.html
[catalog]: https://puppet.com/docs/puppet/latest/subsystem_catalog_compilation.html
[file resource]: https://puppet.com/docs/puppet/latest/type.html#file
[environment]: https://puppet.com/docs/puppet/latest/environments_about.html
[auth.conf]: https://puppet.com/docs/puppetserver/latest/config_file_auth.html

The `static_file_content` endpoint returns the standard output of a
[`code-content-command`][] script, which should output the contents of a specific version
of a [file resource][] that has a `source` attribute with a `puppet:///` URI value. That
source must be a file from the `files` or `tasks` directory of a module in a specific [environment][].

Puppet Agent uses this endpoint only when applying a [static catalog][]. This endpoint
is available only when the Puppet master is running Puppet Server, not Ruby Puppet masters, such as the
[deprecated WEBrick Puppet master](https://puppet.com/docs/puppet/latest/services_master_webrick.html).

## `GET /puppet/v3/static_file_content/<FILE-PATH>`

(Introduced in Puppet Server 2.3.0)

To retrieve a specific version of a file at a given environment and path, make an HTTP
request to this endpoint with the required parameters.

The `<FILE-PATH>` segment of the endpoint is required. The path corresponds to the
requested file's path on the Server relative to the given environment's root directory,
and must point to a file in the `*/*/files/**`, `*/*/lib/**`, or `*/*/tasks/**` glob. For
example, Puppet Server will inline metadata into static catalogs for file resources
sourcing module files located by default in
`/etc/puppetlabs/code/environments/<ENVIRONMENT>/modules/<MODULE NAME>/files/**`.

### Query parameters

You must also pass two parameters in the GET request:

-   `code_id`: a unique string provided by the [catalog][] that identifies which version
    of the file to return.
-   `environment`: the environment that contains the desired file.

### Response

A successful request to this endpoint returns an `HTTP 200` response code and
`application/octet-stream` Content-Type header, and the contents of the specified file's
requested version in the response body. An unsuccessful request returns an error response
code with a `text/plain` Content-Type header:

-   400: returned when any of the parameters are not provided.
-   403: returned when requesting a file that is not within a module's `files` or `tasks`
directory.
-   500: returned when `code-content-command` is not configured on the server, or
when a requested file or version is not present in a repository.

#### Example response

Consider a server `localhost`, with a versioned file located at
`/modules/example/files/data.txt` in the `production` environment. The version is
identified by a `code_id` of
`urn:puppet:code-id:1:67eb71417fbd736a619c8b5f9bfc0056ea8c53ca;production`, and that version of the file contains `Puppet test`.

If you run this command:

```
curl -i -k 'https://localhost:8140/puppet/v3/static_file_content/modules/example/files/data.txt?code_id=urn:puppet:code-id:1:67eb71417fbd736a619c8b5f9bfc0056ea8c53ca;production&environment=production'
```

Puppet Server returns:

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

> **Note:** The `code-content-command` and `code-id-command` scripts are not provided in a
> default installation or upgrade. For more information about these scripts, see the
> [static catalog documentation](https://puppet.com/docs/puppet/latest/static_catalogs.html).

#### Authorization

Puppet Server **always** authorizes requests made to the `static_file_content` API endpoint
with the [Trapperkeeper-based `auth.conf` feature][auth.conf] introduced in Puppet Server
2.2. This is different than most other Puppet master service-based endpoints, for which
the authorization mechanism is controlled by the `use-legacy-auth-conf` setting in the
`jruby-puppet` configuration section. The value of the `use-legacy-auth-conf` setting is
ignored for the `static_file_content` API endpoint, and Puppet Server **never** uses the
legacy `auth.conf` mechanism when authorizing requests. For more information about
authorization options, see the [`auth.conf` documentation][auth.conf].
