---
layout: default
title: "Puppet Server Configuration Files: ca.conf"
canonical: "/puppetserver/latest/config_file_ca.html"
---

[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[new `auth.conf`]: ./conf_file_auth.html
[Puppet `auth.conf`]: /puppet/latest/reference/config_file_auth.html
[deprecated]: ./deprecated_settings.html
[`puppetserver.conf`]: ./conf_file_puppetserver.html

The `ca.conf` file configures legacy settings for the Puppet Server Certificate Authority (CA) service. For a broader overview of Puppet Server configuration, see the [configuration documentation](./configuration.html).

> **Deprecation Note:** This file only supports the `authorization-required` and `client-whitelist` settings, which are deprecated as of Puppet Server 2.2 in favor of new authorization method configured in the [new `auth.conf`][] file. Since these settings are deprecated and the new authorization methods are enabled by default, Puppet Server no longer includes a default `ca.conf` file in the Puppet Server package.

The `certificate-status` setting in `ca.conf` provides legacy configuration options for access to the `certificate_status` and `certificate_statuses` HTTP endpoints. These endpoints allow certificates to be signed, revoked, and deleted via HTTP requests, which provides full control over Puppet's ability to securely authorize access; therefore, you should **always** restrict access to `ca.conf`.

> **Puppet Enterprise Note:** Puppet Enterprise uses these endpoints to provide a console interface for certificate signing. For more information, see the [Certificate Status documentation](/puppet/latest/reference/http_api/http_certificate_status.html).

This setting takes two parameters: `authorization-required` and `client-whitelist`. If neither parameter is specified, or if the `client-whitelist` is specified but empty, Puppet Server uses the [new authorization methods][`trapperkeeper-authorization`] and [new `auth.conf`][] introduced in Puppet Server 2.2 to control access to certificate status endpoints.

* `authorization-required` determines whether a client certificate is required to access certificate status endpoints. If this parameter is set to `false`, all requests can access this API. If set to `true`, only the clients whose certificate names are included in the `client-whitelist` setting can access the admin API. If this parameter is not specified but the `client-whitelist` parameter is, this parameter's value defaults to `true`.
* `client-whitelist` contains a list of client certificate names that are whitelisted for access to the certificate status endpoints. Puppet Server denies access to requests at these endpoints that do not present a valid client certificate named in this list.

