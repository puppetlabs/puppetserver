---
layout: default
title: 'Puppet Server HTTP API: Index'
---

Puppet Server provides several services via HTTP API, and the Puppet agent application uses those services to resolve a node's credentials, retrieve a configuration catalog, retrieve file data, and submit reports.

Many of these endpoints are the same as the [Puppet HTTP API](https://puppet.com/docs/puppet/latest/http_api/http_api_index.html).

## V1/V2 HTTP APIs (removed)

The V1 and V2 APIs were removed in Puppet 4.0.0. The routes that were previously under `/` or `/v2.0` can now be found under the [`/puppet/v3`](#puppet-v3-http-api) API or [`/puppet-ca/v1`](#ca-v1-http-api) API.

Starting with Puppet Server 2.1, Server provides both the current and previous API endpoints, and can serve nodes running Puppet agent 3.x and 4.x. However, Rack masters, WEBrick masters, and Puppet Server 2.0 and earlier cannot serve nodes running Puppet 3.x.

## Puppet and Puppet CA APIs

Beginning with Puppet 4, Puppet's HTTP API was split into two separately versioned APIs:

-   An API for configuration-related services
-   An API for the certificate authority (CA).

All configuration endpoints are prefixed with `/puppet`, while all CA endpoints are prefixed with `/puppet-ca`. All endpoints are explicitly versioned: the prefix is always immediately followed by a string like `/v3` (a directory separator, the letter `v`, and the version number of the API).

### Authorization

Authorization for `/puppet` endpoints is still controlled with Puppet's `auth.conf` authorization system.

Puppet Server ignores `auth.conf` for `/puppet-ca` endpoints. Access to the `certificate_status` endpoint is configured in Puppet Server's `ca.conf` file, and the remaining CA endpoints are always accessible. Rack Puppet master servers still use `auth.conf` for `/puppet-ca`.

When specifying authorization in `auth.conf`, the prefix, and the version number (such as `/puppet/v3`) on the paths must be retained, since Puppet matches authorization rules against the full request path.

## Puppet V3 HTTP API

The Puppet agent application uses several network services to manage systems. These services are all grouped under the `/puppet` API. Other tools can access these services and use the Puppet master's data for other purposes.

The V3 API contains endpoints of two types: those that are based on dispatching to Puppet's internal "indirector" framework, and those that are not (namely the [environment endpoints](#environment-endpoints)).

Every HTTP endpoint that dispatches to the indirector follows the form `/puppet/v3/:indirection/:key?environment=:environment`, where:

-   `:environment` is the name of the environment that should be in effect for the request. Not all endpoints need an environment, but the query parameter must always be specified.
-   `:indirection` is the indirection to which the request is dispatched.
-   `:key` is the "key" portion of the indirection call.

Using this API requires significant understanding of how Puppet's internal services are structured, but the following documents specify what is available and how to interact with it.

### Configuration management services

The Puppet agent application directly uses these servcies to manage the configuration of a node.

These endpoints accept payload formats formatted as JSON or PSON (MIME types of `application/json` and `text/pson`, respectively) except for `File Content` and `File Bucket File`, which always use `application/octet-stream`.

-   [Facts](https://puppet.com/docs/puppet/latest/http_api/http_facts.html)
-   [Catalog](https://puppet.com/docs/puppet/latest/http_api/http_catalog.html)
-   [Node](https://puppet.com/docs/puppet/latest/http_api/http_node.html)
-   [File bucket file](https://puppet.com/docs/puppet/latest/http_api/http_file_bucket_file.html)
-   [File content](https://puppet.com/docs/puppet/latest/http_api/http_file_content.html)
-   [File metadata](https://puppet.com/docs/puppet/latest/http_api/http_file_metadata.html)
-   [Report](https://puppet.com/docs/puppet/latest/http_api/http_report.html)

### Informational services

These services are not directly used by Puppet agent, but can be used by other tools.

-   [Status](https://puppet.com/docs/puppet/latest/http_status.html)

### Environment endpoints

The `/puppet/v3/environments` and `/puppet/v3/environment/:environment` endpoints use a different format than the configuration management and informational services endpoints.

These endpoints accept only payloads formatted as JSON, and respond with JSON (MIME type of `application/json`).

-   [Environments](https://puppet.com/docs/puppet/latest/http_api/http_environments.html)
-   [Environment catalog](https://puppet.com/docs/puppet/latest/http_api/http_environment.html)

### Puppet Server-specific endpoints

Puppet Server adds several unique endpoints of its own. They include these additional `/puppet/v3/` endpoints:

-   [Environment classes](./puppet-api/v3/environment_classes.markdown), at `/puppet/v3/environment_classes`
-   [Environment modules](./puppet-api/v3/environment_modules.markdown), at `/puppet/v3/environment_modules`
-   [Static file content](./puppet-api/v3/static_file_content.markdown), at `/puppet/v3/static_file_content`

It also includes these unique APIs, with endpoints containing other URL prefixes:

-   [Status API](./status-api/v1/services.markdown), at `/status/v1/services`
-   [Metrics v1 (mbeans) API](./metrics-api/v1/metrics_api.markdown), at `/metrics/v1/mbeans`
-   [Metrics v2 (Jolokia) API](./metrics-api/v2/metrics_api.markdown), at `/metrics/v2/`
-   Admin API, at `/puppet-admin-api/v1/`:
    -   [Environment cache](./admin-api/v1/environment-cache.markdown), at `/puppet-admin-api/v1/environment-cache`
    -   [JRuby pool](./admin-api/v1/jruby-pool.markdown), at `/puppet-admin-api/v1/jruby-pool`

### Error responses

The `environments` endpoint responds to error conditions in a uniform manner and uses standard HTTP response codes to signify those errors.

| Request problem | HTTP API error response code |
|-----------------|------------------------------|
| Client submits malformed request | 400 Bad Request |
| Unauthorized client | 403 Not Authorized |
| Client uses an HTTP method not permitted for the endpoint | 405 Method Not Allowed |
| Client requests a response in a format other than JSON | 406 Unacceptable |
| Server encounters an unexpected error while handling a request | 500 Server Error |
| Server can't find an endpoint handler for an HTTP request | 404 Not Found |

Except for HEAD requests, error responses contain a body of a uniform JSON object with the following properties:

-   `message`: (`String`) A human-readable message explaining the error.
-   `issue_kind`: (`String`) A unique label to identify the error class.

There is also a [JSON schema for error objects](../schemas/error.json).

## CA V1 HTTP API

The certificate authority (CA) API contains all of the endpoints supporting Puppet's public key infrastructure (PKI) system.

The CA V1 endpoints share the same basic format as the Puppet V3 API, since they are also based off of Puppet's internal "indirector". However, they have a different prefix and version. These endpoints follow the form `/puppet-ca/v1/:indirection/:key?environment=:environment`, where:

-   `:environment` is an arbitrary placeholder word, required for historical reasons. No CA endpoints actually use an environment, but the query parameter must always be specified.
-   `:indirection` is the indirection to which the request is dispatched.
-   `:key` is the "key" portion of the indirection call.

As with the Puppet V3 API, using this API requires a significant amount of understanding of how Puppet's internal services are structured. The following documents specify what is available and how to interact with it.

### SSL certificate-related services

These endpoints accept only plain-text payload formats. Historically, Puppet has used the MIME type `s` to mean `text/plain`. In Puppet 5, it always uses `text/plain`, but continues to accept `s` as an equivalent.

-   [Certificate](https://puppet.com/docs/puppet/latest/http_api/http_certificate.html)
-   [Certificate Signing Requests](https://puppet.com/docs/puppet/latest/http_api/http_certificate_request.html)
-   [Certificate Status](https://puppet.com/docs/puppet/latest/http_api/http_certificate_status.html)
-   [Certificate Revocation List](https://puppet.com/docs/puppet/latest/http_api/http_certificate_revocation_list.html)

## Serialization formats

Puppet sends messages using several serialization formats. Not all REST services support all of the formats.

-   [JSON](https://tools.ietf.org/html/rfc7159)
-   [PSON](https://puppet.com/docs/puppet/latest/http_api/pson.html)

`YAML` was supported in earlier versions of Puppet, but is no longer for security reasons.