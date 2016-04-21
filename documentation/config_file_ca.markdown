---
layout: default
title: "Puppet Server Configuration Files: ca.conf"
canonical: "/puppetserver/latest/config_file_ca.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[new `auth.conf`]: ./config_file_auth.html
[deprecated]: ./deprecated_features.html

The `ca.conf` file configures settings for the [deprecated][] Puppet Server Certificate Authority (CA) service. For an overview, see [Puppet Server Configuration](./configuration.html).

> **Deprecation Note:** This file supports only the `authorization-required` and `client-whitelist` settings, which are [deprecated][] as of Puppet Server 2.2 in favor of authorization that is configured in the [new `auth.conf`][] file. Because these settings are deprecated, a default `ca.conf` file is no longer included in the Puppet Server package.

## Settings

The `certificate-status` setting in `ca.conf` provides [deprecated][] configuration options for access to the `certificate_status` and `certificate_statuses` HTTP endpoints. These endpoints allow certificates to be signed, revoked, and deleted through HTTP requests, which provides full control over Puppet's ability to securely authorize access. Therefore, you should **always** restrict access to `ca.conf`.

> **Puppet Enterprise Note:** Puppet Enterprise uses these endpoints to provide a console interface for certificate signing. For more information, see [Certificate Status](/puppet/latest/reference/http_api/http_certificate_status.html).

The `certificate-status` setting takes two parameters: `authorization-required` and `client-whitelist`. If `authorization-required` is set to `true` or not set, **and** `client-whitelist` is set to an empty list or not set, Puppet Server uses the [authorization methods][`trapperkeeper-authorization`] and [`auth.conf`][] format introduced in Puppet Server 2.2 to control access to the administration API endpoints.

* `authorization-required` determines whether a client certificate is required to access certificate status endpoints. If this parameter is set to `false`, all requests can access this API. If set to `true`, only the clients whose certificate names are included in the `client-whitelist` setting can access the admin API. If this parameter is not specified but the `client-whitelist` parameter is, this parameter's value defaults to `true`.
* `client-whitelist` contains a list of client certificate names that are whitelisted for access to the certificate status endpoints. Puppet Server denies access to requests at these endpoints that do not present a valid client certificate named in this list.

## Example (Deprecated)

If you are using the deprecated authorization methods, follow this structure to configure `certificate_status` and `certificate_statuses` endpoint access in `ca.conf`, whitelisting a client named `host1`:

~~~
# CA-related settings - deprecated in favor of "auth.conf"
certificate-authority: {
   certificate-status: {
       authorization-required: true
       client-whitelist: [host1]
   }
}
~~~