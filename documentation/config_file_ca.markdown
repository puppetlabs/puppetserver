---
layout: default
title: "Puppet Server Configuration Files: ca.conf"
canonical: "/puppetserver/latest/config_file_ca.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[new `auth.conf`]: ./config_file_auth.markdown
[deprecated]: ./deprecated_features.markdown

The `ca.conf` file configures settings for the Puppet Server Certificate Authority (CA) service. For an overview, see [Puppet Server Configuration](./configuration.markdown).

> **Deprecation Note:** The `authorization-required` and `client-whitelist` settings are [deprecated][] as of Puppet Server 2.2 in favor of authorization that is configured in the [new `auth.conf`][] file.

## Signing settings

The `allow-subject-alt-names` setting in the `certificate-authority` section enables you to sign certs with subject alternative names. It is false by default for security reasons, but can be enabled if you need to sign certs with subject alternative names. `puppet cert sign` used to allow this via a flag, but `puppetserver ca sign` requires it to be configured in the config file.

The `allow-authorization-extensions` setting in the `certificate-authority` section enables you to sign certs with authorization extensions. It is false by default for security reasons, but can be enabled if you know you need to sign certs this way. `puppet cert sign` used to allow this via a flag, but `puppetserver ca sign` requires it to be configued in the config file.

## Infrastructure CRL settings

Puppet Server is able to create a separate CRL file containing only revocations of Puppet infrastructure nodes. This behavior is turned off by default. To enable it, set `certificate-authority.enable-infra-crl` to `true`.

## Status settings (deprecated)

The `certificate-status` setting in `ca.conf` provides [deprecated][] configuration options for access to the `certificate_status` and `certificate_statuses` HTTP endpoints. These endpoints allow certificates to be signed, revoked, and deleted through HTTP requests, which provides full control over Puppet's ability to securely authorize access. Therefore, you should **always** restrict access to `ca.conf`.

> **Puppet Enterprise Note:** Puppet Enterprise uses these endpoints to provide a console interface for certificate signing. For more information, see [Certificate Status](https://puppet.com/docs/puppet/latest/http_api/http_certificate_status.html).

The `certificate-status` setting takes two parameters: `authorization-required` and `client-whitelist`. If `authorization-required` is set to `true` or not set, **and** `client-whitelist` is set to an empty list or not set, Puppet Server uses the [authorization methods][`trapperkeeper-authorization`] and [new `auth.conf`][] format introduced in Puppet Server 2.2 to control access to the administration API endpoints.

* `authorization-required` determines whether a client certificate is required to access certificate status endpoints. If this parameter is set to `false`, all requests can access this API. If set to `true`, only the clients whose certificate names are included in the `client-whitelist` setting can access the admin API. If this parameter is not specified but the `client-whitelist` parameter is, this parameter's value defaults to `true`.
* `client-whitelist` contains a list of client certificate names that are whitelisted for access to the certificate status endpoints. Puppet Server denies access to requests at these endpoints that do not present a valid client certificate named in this list.

## Example (Deprecated)

If you are using the deprecated authorization methods, follow this structure to configure `certificate_status` and `certificate_statuses` endpoint access in `ca.conf`, whitelisting a client named `host1`:

~~~
certificate-authority: {
   # deprecated in favor of auth.conf
   certificate-status: {
       authorization-required: true
       client-whitelist: [host1]
   }
}
~~~
