---
layout: default
title: "Puppet Server Configuration Files: master.conf"
canonical: "/puppetserver/latest/config_file_master.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[new `auth.conf`]: ./config_file_auth.html
[Puppet `auth.conf`]: /puppet/latest/reference/config_file_auth.html
[deprecated]: ./deprecated_features.html
[`puppetserver.conf`]: ./config_file_puppetserver.html

The `master.conf` file configures how Puppet Server handles [deprecated][] authorization methods for master endpoints. For an overview, see [Puppet Server Configuration](./configuration.html).

> **Deprecation Note:** This file contains only the `allow-header-cert-info` parameter, and is deprecated as of Puppet Server 2.2 in favor of authorization settings that are configured in the [`auth.conf`][] file. Because this setting is deprecated, a default `master.conf` file is no longer included in the Puppet Server package.

In `master.conf`, the `allow-header-cert-info` setting determines whether Puppet Server should use authorization info from the `X-Client-Verify`, `X-Client-DN`, and `X-Client-Cert` HTTP headers. Its default value is `false`.

The `allow-header-cert-info` setting is used to enable [external SSL termination](./external_ssl_termination.markdown). If the setting's value is set to `true`, Puppet Server will ignore any certificate presented to the Jetty web server, and will rely on header data to authorize requests. This is very dangerous unless you've secured your network to prevent any untrusted access to Puppet Server.

When using the `allow-header-cert-info` setting in `master.conf`, you can change Puppet's `ssl_client_verify_header` parameter to use another header name instead of `X-Client-Verify`. The `ssl_client_header` parameter can rename `X-Client-DN`. The `X-Client-Cert` header can't be renamed. 

The `allow-header-cert-info` parameter in `master.conf` applies only to HTTP endpoints served by the "master" service. The applicable endpoints include those listed in [Puppet V3 HTTP API](/puppet/latest/reference/http_api/http_api_index.html#puppet-v3-http-api). It does not apply to the endpoints listed in [CA V1 HTTP API](/puppet/latest/reference/http_api/http_api_index.html#ca-v1-http-api) or to any [Puppet Admin API][`puppetserver.conf`] endpoints.

## Supported Authorization Workflow

If you instead enable the `auth.conf` authorization method introduced in Puppet Server 2.2, the value of the `allow-header-cert-info` parameter in `auth.conf` controls how the user's identity is derived for authorization purposes. In this case, Puppet Server ignores the value of the `allow-header-cert-info` parameter in `master.conf`.

When using the `allow-header-cert-info` parameter in `auth.conf`, none of the `X-Client` headers can be renamed. Identity must be specified through the `X-Client-Verify`, `X-Client-DN`, and `X-Client-Cert` headers.

The `allow-header-cert-info` parameter in `auth.conf`, applies to all HTTP endpoints that Puppet Server handles, including those served by the "master" service, the CA API, and the Puppet Admin API.

For additional information on the `allow-header-cert-info` parameter in `auth.conf`, see [Puppet Server Configuration Files: `auth.conf`][new `auth.conf`] and [Configuring the Authorization Service in the `trapperkeeper-authorization` documentation](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md#allow-header-cert-info).

#### HOCON `auth.conf` Example

~~~ hocon
authorization: {
    version: 1
    # allow-header-cert-info: false
    rules: [
        {
            # Allow nodes to retrieve their own catalog
            match-request: {
                path: "^/puppet/v3/catalog/([^/]+)$"
                type: regex
                method: [get, post]
            }
            allow: "$1"
            sort-order: 500
            name: "puppetlabs catalog"
        },
        ...
    ]
}
~~~