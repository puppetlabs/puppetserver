---
layout: default
title: "Puppet Server: External SSL Termination"
canonical: "/puppetserver/latest/external_ssl_termination.html"
---


In network configurations that require external SSL termination, there are some important differences between configuring the Apache/Passenger stack and configuring Puppet Server. Use the following steps to configure external SSL termination.

## Disable HTTPS for Puppet Server

You'll need to turn off SSL and have Puppet Server use the HTTP protocol instead: remove the `ssl-port` and `ssl-host` settings from the `conf.d/webserver.conf` file and replace them with `port` and `host` settings. See [Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md) for more information on configuring the web server service.

## Allow Client Cert Data From HTTP Headers

When using external SSL termination, Puppet Server expects to receive client certificate information via some HTTP headers.

By default, reading this data from headers is disabled.  To allow Puppet Server
to recognize it, you'll need to set the `allow-header-cert-info` setting to `true`.

Prior to the inclusion of `trapperkeeper-authorization` and an `auth.conf` file
specific to Puppet Server, you would need to edit (or create) `conf.d/master.conf`
and add `allow-header-cert-info: true` to the `master` config section.  See
[Puppet Server Configuration](./configuration.markdown) for more information on
the `master.conf` file.  This approach, however, is now deprecated.

Instead, it is preferred to enable `trapperkeeper-authorization` and
set the `allow-header-cert-info` setting via the `authorization` config
section.  This involves the following steps:

* Migrate any custom authorization rule definitions that you may have made to core Puppet's
 `/etc/puppetlabs/puppet/auth.conf` file over to the
 `/etc/puppetlabs/puppetserver/conf.d/auth.conf` file.
* Set the `jruby-puppet.use-legacy-auth-conf` setting in the
 `conf.d/puppetserver.conf` file to `false`.
* Add `allow-header-cert-info: true` to the `authorization` config section in
 the `/etc/puppetlabs/puppetserver/conf.d/auth.conf` file.

See [Puppet Server Configuration](./configuration.markdown) for more information
on the `puppetserver.conf` and `auth.conf` files.

> **WARNING**: Setting `allow-header-cert-info` to 'true' puts Puppet Server in an incredibly vulnerable state. Take extra caution to ensure it is **absolutely not reachable** by an untrusted network.
>
> With `allow-header-cert-info` set to 'true', authorization code will use only the client HTTP header values---not an SSL-layer client certificate---to determine the client subject name, authentication status, and trusted facts. This is true even if the web server is hosting an HTTPS connection. This applies to validation of the client via rules in the [auth.conf](https://docs.puppetlabs.com/guides/rest_auth_conf.html) file and any [trusted facts][trusted] extracted from certificate extensions.
>
> If the `client-auth` setting in the `webserver` config block is set to `need` or `want`, the Jetty web server will still validate the client certificate against a certificate authority store, but it will only verify the SSL-layer client certificate---not a certificate in an  `X-Client-Cert` header.


## Restart Puppet Server

You'll need to restart Puppet Server for the configuration changes to take effect.

## Configure SSL Terminating Proxy to Set HTTP Headers

The device that terminates SSL for Puppet Server must extract information from the client's certificate and insert that information into three HTTP headers. See the documentation for your SSL terminator for details.

The headers you'll need to set are `X-Client-Verify`, `X-Client-DN`, and `X-Client-Cert`.

### `X-Client-Verify`

Mandatory. Must be either `SUCCESS` if the certificate was validated, or something else if not. (The convention seems to be to use `NONE` for when a certificate wasn't presented, and `FAILED:reason` for other validation failures.) Puppet Server uses this to authorize requests; only requests with a value of `SUCCESS` will be considered authenticated.

When using the `master.allow-header-cert-info: true` setting, you can change this header name with [the `ssl_client_verify_header` setting.](https://docs.puppetlabs.com/references/latest/configuration.html#sslclientverifyheader)

This setting (and its twin, `ssl_client_header`) is a bit odd: its value should be the result of transforming the desired HTTP header name into a CGI-style environment variable name. That is, to change the HTTP header to `X-SSL-Client-Verify`, you would set the setting to `HTTP_X_SSL_CLIENT_VERIFY`. (Add `HTTP_` to the front, change hyphens to underscores, and uppercase everything.)

(Puppet Server will eventually UN-munge the CGI variable name to get a valid HTTP header name, and use that name to interact directly with an HTTP request. This is a legacy quirk to ensure that the setting works the same for both Puppet Server and a Rack Puppet master; note that Rack actually does use CGI environment variables.)

Note that if you are using the `authorization.allow-header-cert-info: true`
setting, the name of the client verify header in the request must be
`X-Client-Verify`; the `ssl_client_verify_header` setting in the `puppet.conf`
file has no effect on how the client verify header is processed.

### `X-Client-DN`

Mandatory. Must be the [Subject DN][] of the agent's certificate, if a certificate was presented. Puppet Server uses this to authorize requests.

> **Note:** Currently, when using `master.allow-header-cert-info: true`, the DN must be in RFC-2253 format (comma-delimited). Due to a bug ([SERVER-213](https://tickets.puppetlabs.com/browse/SERVER-213)), Puppet Server can't decode OpenSSL-style DNs (slash-delimited). Note that Apache's `mod_ssl` `SSL_CLIENT_S_DN` variable uses OpenSSL-style DNs.  Note
 that the bug does not apply when `authorization.allow-header-cert-info` is set
 to true.  `trapperkeeper-authorization` supports decoding both the RFC-2253
 and OpenSSL "slash-delimited" DN formats.

When using the `master.allow-header-cert-info: true` setting, you can change this header name with [the `ssl_client_header` setting.](https://docs.puppetlabs.com/references/latest/configuration.html#sslclientheader) See the note above for more info about this setting's expected values.

Note that if you are using the `authorization.allow-header-cert-info: true`
setting, the name of the client DN header in the request must be
`X-Client-DN`; the `ssl_client_header` setting in the `puppet.conf` file has no
effect on how the client DN header is processed.

[subject dn]: https://docs.puppetlabs.com/background/ssl/cert_anatomy.html#the-subject-dn-cn-certname-etc

### `X-Client-Cert`

Optional. Should contain the client's [PEM-formatted][pem format] (Base-64) certificate (if a certificate was presented) in a single URI-encoded string. Note that URL encoding is not sufficient; all space characters must be encoded as `%20` and not `+` characters.

> **Note:** Puppet Server only uses the value of this header to extract [trusted facts][trusted] from extensions in the client certificate. If you aren't using trusted facts, you can choose to reduce the size of the request payload by omitting the `X-Client-Cert` header.

> **Note:** Apache's `mod_proxy` converts line breaks in PEM documents to spaces for some reason, and Puppet Server can't decode the result. We're tracking this issue as [SERVER-217](https://tickets.puppetlabs.com/browse/SERVER-217).

The name of this header is not configurable.


[pem format]: https://docs.puppetlabs.com/background/ssl/cert_anatomy.html#pem-file
[trusted]: https://docs.puppetlabs.com/puppet/latest/reference/lang_facts_and_builtin_vars.html#trusted-facts
