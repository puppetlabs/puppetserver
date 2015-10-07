---
layout: default
title: "Puppet Server Configuration Files: master.conf"
canonical: "/puppetserver/latest/config_file_master.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[new `auth.conf`]: ./conf_file_auth.html
[Puppet `auth.conf`]: /puppet/latest/reference/config_file_auth.html
[deprecated]: ./deprecated_settings.html
[`puppetserver.conf`]: ./conf_file_puppetserver.html

The `master.conf` file configures how Puppet Server handles legacy authorization methods for master endpoints. For a broader overview of Puppet Server configuration, see the [configuration documentation](./configuration.html).

> **Deprecation Note:** This file only supports the `allow-header-cert-info` parameter, and is deprecated as of Puppet Server 2.2 in favor of new authorization methods configured in the [new `auth.conf`][] file. Since this configuration file is deprecated, Puppet Server no longer includes a default `master.conf` file in the Puppet Server package.

* In `master.conf`, `allow-header-cert-info` determines whether Puppet Server should use authorization info from the `X-Client-Verify`, `X-Client-CN`, and `X-Client-Cert` HTTP headers. Defaults to `false`.

    This setting is used to enable [external SSL termination](./external_ssl_termination.markdown). If enabled, Puppet Server will ignore any actual certificate presented to the Jetty webserver, and will rely completely on header data to authorize requests. This is very dangerous unless you've secured your network to prevent any untrusted access to Puppet Server.

    When using the `allow-header-cert-info` parameter in `master.conf`, you can change Puppet's `ssl_client_verify_header` parameter to use another header name instead of `X-Client-Verify`; the `ssl_client_header` parameter can rename `X-Client-CN`. The `X-Client-Cert` header can't be renamed. 

    Note that the `allow-header-cert-info` parameter in `master.conf` only applies to HTTP endpoints served by the "master" service. The applicable endpoints include those listed in the [Puppet V3 HTTP API](https://docs.puppetlabs.com/puppet/4.2/reference/http_api/http_api_index.html#puppet-v3-http-api). It does not apply to the endpoints listed in the [CA V1 HTTP API](https://docs.puppetlabs.com/puppet/4.2/reference/http_api/http_api_index.html#ca-v1-http-api) or to any of the [Puppet Admin API][`puppetserver.conf`] endpoints.

    If the new Puppet Server authorization method is enabled, the value of the `allow-header-cert-info` parameter in `auth.conf` controls how the user's identity is derived for authorization purposes. In this case, Puppet Server ignores the value of the `allow-header-cert-info` parameter in `master.conf`.

    When using the `allow-header-cert-info` parameter in `auth.conf`, however, none of the `X-Client` headers can be renamed; identity must be specified through the `X-Client-Verify`, `X-Client-CN`, and `X-Client-Cert` headers.
    
    The `allow-header-cert-info` parameter in `auth.conf`, however, applies to all HTTP endpoints that Puppet Server handles, including ones served by the "master" service and the CA and Puppet Admin APIs.

For detailed information on the new `allow-header-cert-info` parameter in `auth.conf`, see the [`Configuring the Authorization Service` page of the `trapperkeeper-authorization` documentation](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md#allow-header-cert-info).

#### HOCON `auth.conf` Examples

~~~
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

For more information on this configuration file format, see
[the `trapperkeepr-authorization` documentation](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md).