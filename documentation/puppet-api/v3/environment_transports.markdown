---
layout: default
title: "Puppet Server: Puppet API: Environment Transports"
canonical: "/puppetserver/latest/puppet-api/v3/environment_transports.html"
---

[Resource API Transports]: https://puppet.com/docs/puppet/latest/about_the_resource_api.html#resource-api-transports
[environment cache API]: ../../admin-api/v1/environment-cache.markdown
[environment classes API]: ./environment_classes.markdown
[transports schema]: ./environment_transports.json
[`auth.conf` documentation]: ../../config_file_auth.markdown

The environment transports API returns a JSON object representing the
requested environment and schemas for all available [Resource API Transports][].
The endpoint follows all conventions set by the [environment classes API][]
including request format, etag validation with expiration managed by the
[environment cache API][], and errors.

## `GET /puppet/v3/environment_transports?environment=<environment>`

(Introduced in Puppet Server 6.4.0)


### Query Parameters

#### `environment` (required)
The name of the environment to query for available device transport schemas.

### Schema

The transports endpoint response body conforms to the [transports schema][].

### Authorization

All requests made to the environment transports API are authorized using the
Trapperkeeper-based `auth.conf`.  For more information about the Puppet Server
authorization process and configuration settings, see the
[`auth.conf` documentation][].
