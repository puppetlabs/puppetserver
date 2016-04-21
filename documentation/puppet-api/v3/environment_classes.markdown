---
layout: default
title: "Puppet Server: Puppet API: Environment Classes"
canonical: "/puppetserver/latest/puppet/v3/environment_classes.html"
---

[classes]: /puppet/latest/reference/lang_classes.html
[deprecated WEBrick Puppet master]: /puppet/latest/reference/services_master_webrick.html
[node definitions]: /puppet/latest/reference/lang_node_definitions.html
[defined types]: /puppet/latest/reference/lang_defined_types.html
[`environment_timeout`]: /puppet/latest/reference/config_file_environment.html#environmenttimeout
[resource type API]: /puppet/latest/reference/http_api/http_resource_type.html
[`manifest` setting]: /puppet/latest/reference/config_file_environment.html#manifest

[`auth.conf`]: /puppetserver/latest/config_file_auth.html
[`puppetserver.conf`]: /puppetserver/latest/config_file_puppetserver.html
[environment cache API]: /puppetserver/latest/admin-api/v1/environment-cache.html

[Etag]: https://tools.ietf.org/html/rfc7232#section-2.3

The environment classes API serves as a replacement for the Puppet [resource type API][]
for [classes][].

This endpoint is available only when the Puppet master is running Puppet Server, not
on Ruby Puppet masters, such as the
[deprecated WEBrick Puppet master][]. It also ignores the Ruby-based Puppet master's
authorization methods and configuration. See the [Authorization section](#authorization)
for more information.

## Changes in the environment classes API

Compared to the resource type API, the environment classes API covers different things,
returns new or different information, and omits some information.

### Covers classes only

The environment classes API covers only [classes][], whereas the resource type API covers
classes, [node definitions][], and [defined types][].

### Changes class information caching behavior

Queries to the resource type API use cached class information per the configuration of the
[`environment_timeout`][] setting, as set in the corresponding environment's
`environment.conf` file. The environment classes API does not use the value of
`environment_timeout` with respect to the data that it caches. Instead, only when the
`environment-class-cache-enabled` setting in the `jruby-puppet` configuration section is
set to `true`, the environment classes API uses HTTP [Etags][Etag] to represent specific
versions of the class information. And it uses the Puppet Server [environment cache API][] as an
explicit mechanism for marking an Etag as expired. See the
[Headers and caching behavior](#headers-and-caching-behavior) section for more information
about caching and invalidation of entries.

### Uses typed values

The environment classes API includes a `type`, if defined for a class parameter. For
example, if the class parameter were defined as `String $some_str`, the `type` parameter
would hold a value of `String`.

### Provides default literal values

For values that can be presented in pure JSON, the environment classes API provides a
`default_literal` form of a class parameter's default value. For example, if an `Integer`
type class parameter were defined in the manifest as having a default value of `3`, the
`default_literal` element for the parameter will contain a JSON Number type of 3.

### Lacks filters

The environment classes API does not provide a way to filter the list of classes returned
via use of a search string. The environment classes API returns information for all
classes found within an environment's manifest files.

### Includes filenames

Unlike the resource type API in Puppet 4, the environment classes API does include the
filename in which each class was found. The resource type API in Puppet 3 does include
the filename, but the resource type API under Puppet 4 does not.

### Lacks line numbers

The environment classes API does not include the line number at which a class is found in
the file.

### Lacks documentation strings (vs. Puppet 3)

Unlike the resource type API in Puppet 3, the environment classes API does not include
any doc strings for a class entry. Note that doc strings are also not returned for class
entries in the Puppet 4 resource type API.

### Returns file entries for manifests with no classes

The environment classes API returns a file entry for manifests that exist in the
environment but in which no classes were found. The resource type API omits entries for
files which do not contain any classes.

### Uses `application/json` Content-Type

The Content-Type in the response to an environment classes API query is
`application/json`, whereas the resource type API uses a Content-Type of `text/pson`.

### Includes successfully parsed classes, even if some return errors, and returns error messages

The environment classes API includes information for every class that can successfully
be parsed. For any errors which occur when parsing individual manifest files, the response
includes an entry for the corresponding manifest file, along with an error and detail
string about the failure.

In comparison, if an error is encountered when parsing a manifest, the resource type API omits
information from the manifest entirely. It includes class information from other manifests that it
successfully parsed, assuming none of the parsing
errors were found in one of the files associated with the environment's
[`manifest` setting][]. If one or more classes is returned but errors were
encountered parsing other manifests, the response from the resource type API call doesn't
include any explicit indication that a parsing error was encountered.

## `GET /puppet/v3/environment_classes?environment=:environment`

(Introduced in Puppet Server 2.3.0.)

Making a request with no query parameters is not supported and returns an HTTP 400 (Bad
Request) response.

### Supported HTTP Methods

GET

### Supported Formats

JSON

### Query Parameters

Provide one parameter to the GET request:

* `environment`: Only the classes and parameter information pertaining to the specified
environment will be returned for the call.

### Responses

#### GET request with results

```
GET /puppet/v3/environment_classes?environment=env

HTTP/1.1 200 OK
Etag: b02ede6ecc432b134217a1cc681c406288ef9224
Content-Type: text/json

{
  "files": [
    {
      "path": "/etc/puppetlabs/code/environments/env/manifests/site.pp",
      "classes": []
    },
    {
      "path": "/etc/puppetlabs/code/environments/env/modules/mymodule/manifests/init.pp",
      "classes": [
        {
          "name": "mymodule",
          "params": [
            {
              "default_literal": "this is a string",
              "default_source": "\"this is a string\"",
              "name": "a_string",
              "type": "String"
            },
            {
              "default_literal": 3,
              "default_source": "3",
              "name": "an_integer",
              "type": "Integer"
            }
          ]
        }
      ]
    },
    {
      "error": "Syntax error at '=>' at /etc/puppetlabs/code/environments/env/modules/mymodule/manifests/other.pp:20:19",
      "path": "/etc/puppetlabs/code/environments/env/modules/mymodule/manifests/other.pp"
    }
  ],
  "name": "env"
}
```

#### GET request with Etag roundtripped from a previous GET request

If you send the [Etag][] value that was returned from the previous request to the server in a follow-up request,  and the underlying environment
cache has not been invalidated, the server will return an HTTP 304 (Not Modified) response. See
the [Headers and Caching Behavior](#headers-and-caching-behavior) section for more
information about caching and invalidation of entries.

```
GET /puppet/v3/environment_classes?environment=env
If-None-Match: b02ede6ecc432b134217a1cc681c406288ef9224

HTTP/1.1 304 Not Modified
Etag: b02ede6ecc432b134217a1cc681c406288ef9224
```

If the environment cache has been updated from what was used to calculate the original
Etag, the server will return a response with the full set of environment class
information:

```
GET /puppet/v3/environment_classes?environment=env
If-None-Match: b02ede6ecc432b134217a1cc681c406288ef9224

HTTP/1.1 200 OK
Etag: 2f4f83096265b9741c5304b3055f866df0336762
Content-Type: text/json

{
  "files": [
    {
      "path": "/etc/puppetlabs/code/environments/env/manifests/site.pp",
      "classes": []
    },
    {
      "path": "/etc/puppetlabs/code/environments/env/modules/mymodule/manifests/init.pp",
      "classes": [
        {
          "name": "mymodule",
          "params": [
            {
              "default_literal": "this is a string",
              "default_source": "\"this is a string\"",
              "name": "a_string",
              "type": "String"
            },
            {
              "default_literal": 3,
              "default_source": "3",
              "name": "an_integer",
              "type": "Integer"
            },
            {
              "default_literal": {
                "one": "foo",
                "two": "hello"
              },
              "default_source": "{ \"one\" => \"foo\", \"two\" => \"hello\" }",
              "name": "a_hash",
              "type": "Hash"
            }
          ]
        }
      ]
    }
  ],
  "name": "env"
}
```

#### Environment does not exist

If you send a request with an environment parameter that doesn't correspond to the name of a
directory environment on the server, the server returns an HTTP 404 (Not Found) error:

```
GET /puppet/v3/environment_classes?environment=doesnotexist

HTTP/1.1 404 Not Found

Could not find environment 'doesnotexist'
```

#### No environment given

```
GET /puppet/v3/environment_classes

HTTP/1.1 400 Bad Request

You must specify an environment parameter.
```

#### Environment parameter specified with no value

```
GET /puppet/v3/environment_classes?environment=

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not ''
```

#### Environment includes non-alphanumeric characters

If the environment parameter in your request includes any characters that are
not `A-Z`, `a-z`, `0-9`, or `_` (underscore), the server returns an HTTP 400 (Bad Request) error:

```
GET /puppet/v3/environment_classes?environment=bog|us

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not 'bog|us'
```

### Schema

An environment classes response body conforms to the
[environment classes schema](./environment_classes.json).

### Headers and Caching Behavior

If the `environment-class-cache-enabled` setting in the `jruby-puppet` configuration
section is set to `true`, the environment classes API caches the response data. This
can provide a significant performance benefit by reducing the amount of data that
needs to be provided in a response when the underlying Puppet code on disk remains
unchanged from one request to the next. Use of the cache does, however, require that cache
entries are invalidated after Puppet code has been updated.

To avoid invalidated cache entries, you can omit the `environment-class-cache-enabled`
setting from a node's configuration or set it to `false`. In this case, the server
discovers and parses manifests for every incoming request. This can significantly increase
bandwidth overhead for repeated requests, particularly when there are few
changes to the underlying Puppet code. However, this approach ensures that the
latest available data is returned to every request.

#### Behaviors when the environment class cache is enabled

When the `environment-class-cache-enabled` setting is set to `true`, the response to a
query to the `environment_classes` endpoint includes an HTTP [Etag][] header. The value
for the Etag header is a hash that represents the state of the latest class information
available for the requested environment. For example:

```
ETag: 31d64b8038258202b4f5eb508d7dab79c46327bb
```

A client can (but is not required to) provide the Etag value back to the server in a
subsequent `environment_classes` request. The client would provide the tag value as the
value for an [If-None-Match](https://tools.ietf.org/html/rfc7232#section-3.2) HTTP header:

```
If-None-Match: 31d64b8038258202b4f5eb508d7dab79c46327bb
```

If the latest state of code available on the server matches that of the value in the
`If-None-Match` header, the server returns an HTTP 304 (Not Modified) response with no
response body. If the server has newer code available than what is captured by the
`If-None-Match` header value, or if no `If-None-Match` header is provided in the request,
the server parses manifests again. Assuming the resulting payload is different than a
previous request's, the server provides a different Etag value and new class information
in the response payload.

If the client sends an `Accept-Encoding: gzip` HTTP header for the request and the server
provides a gzip-encoded response body, the server might append the characters `--gzip` to
the end of the Etag. For example, the HTTP response headers could include:

```
Content-Encoding: gzip
ETag: e84bbce5482243b3eb3a190e5c90e535cf4f20de--gzip
```

The server accepts both forms of an Etag (with or without the trailing `--gzip`
characters) as the same value when validating it in a request's `If-None-Match` header
against its cache.

It is best, however, for clients to use the Etag without parsing its content. A client
expecting an HTTP 304 (Not Modified) response if the cache has not been updated since the
prior request should provide the exact value returned in the `Etag` header from one
request, to the server in an `If-None-Match` header in a subsequent request for the
environment's class information.

#### Clearing class information cache entries

After updating an environment's manifests, you must clear the server's class information cache entries, so the server can parse the latest manifests and reflect class changes to
the class information in queries to the environment classes endpoint. To clear cache
entries on the server, do one of the following:

-   Call the [`environment-cache` API endpoint][environment cache API].

    For best performance, call this endpoint with a query parameter that specifies the
    environment whose cache should be flushed.

-   **PE only:** Perform a `commit` through the
    [File Sync API endpoint](/pe/latest/cmgmt_filesync_api.html#post-file-syncv1commit).

    When committing code through the file sync API, you don't need to invoke the
    `environment-cache` API endpoint to flush the environment's cache. The environment's
    cache is implicitly flushed as part of the sync of new commits to a master using
    file sync.

-   Restart Puppet Server.

    Each environment's cache is held in memory for the Puppet Server process and is
    effectively flushed whenever Puppet Server is restarted, whether with a
    [HUP signal](./restarting.html) or a full JVM restart.

### Authorization

Unlike other Puppet master service-based API endpoints, the environment classes API is
provided exclusively by Puppet Server. All requests made to the environment
classes API are authorized using the Trapperkeeper-based [`auth.conf`][] feature
introduced in Puppet Server 2.2.0, and ignores the older Ruby-based authorization process
and configuration. The value of the `use-legacy-auth-conf` setting in the `jruby-puppet`
configuration section of [`puppetserver.conf`][] is ignored for requests
to the environment classes API, because the Ruby-based authorization process is not equipped to
authorize these requests.

For more information about the Puppet Server authorization process and configuration
settings, see the [`auth.conf` documentation][`auth.conf`].
