---
layout: default
title: "Puppet Server: External SSL Termination"
canonical: "/puppetserver/latest/external_ssl_termination.html"
---


Use the following steps to configure external SSL termination.

## Disable HTTPS for Puppet Server

You'll need to turn off SSL and have Puppet Server use the HTTP protocol instead: remove the `ssl-port` and `ssl-host` settings from the `conf.d/webserver.conf` file and replace them with `port` and `host` settings. See [Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md) for more information on configuring the web server service.

## Allow Client Cert Data From HTTP Headers

When using external SSL termination, Puppet Server expects to receive client
certificate information via some HTTP headers.

By default, reading this data from headers is disabled.  To allow Puppet
Server to recognize it, you'll need to set `allow-header-cert-info: true`
in the `authorization` config section of the
`/etc/puppetlabs/puppetserver/conf.d/auth.conf` file.

See [Puppet Server Configuration](./configuration.markdown) for more
information on the `puppetserver.conf` and `auth.conf` files.

Note: This assumes the default behavior of Puppet 5 and greater of using
Puppet Server's hocon auth.conf rather Puppet's older ini-style auth.conf.

> **WARNING**: Setting `allow-header-cert-info` to 'true' puts Puppet Server in an incredibly vulnerable state. Take extra caution to ensure it is **absolutely not reachable** by an untrusted network.
>
> With `allow-header-cert-info` set to 'true', authorization code will use only the client HTTP header values---not an SSL-layer client certificate---to determine the client subject name, authentication status, and trusted facts. This is true even if the web server is hosting an HTTPS connection. This applies to validation of the client via rules in the [auth.conf](https://puppet.com/docs/puppet/latest/config_file_auth.html) file and any [trusted facts][trusted] extracted from certificate extensions.
>
> If the `client-auth` setting in the `webserver` config block is set to `need` or `want`, the Jetty web server will still validate the client certificate against a certificate authority store, but it will only verify the SSL-layer client certificate---not a certificate in an  `X-Client-Cert` header.


## Reload Puppet Server

You'll need to reload Puppet Server for the configuration changes to take effect.

## Configure SSL Terminating Proxy to Set HTTP Headers

The device that terminates SSL for Puppet Server must extract information from the client's certificate and insert that information into three HTTP headers. See the documentation for your SSL terminator for details.

The headers you'll need to set are `X-Client-Verify`, `X-Client-DN`, and `X-Client-Cert`.

### `X-Client-Verify`

Mandatory. Must be either `SUCCESS` if the certificate was validated, or something else if not. (The convention seems to be to use `NONE` for when a certificate wasn't presented, and `FAILED:reason` for other validation failures.) Puppet Server uses this to authorize requests; only requests with a value of `SUCCESS` will be considered authenticated.

### `X-Client-DN`

Mandatory. Must be the [Subject DN][] of the agent's certificate, if a
certificate was presented. Puppet Server uses this to authorize requests.

[subject dn]: https://docs.puppet.com/background/ssl/cert_anatomy.html#the-subject-dn-cn-certname-etc

### `X-Client-Cert`

Optional. Should contain the client's [PEM-formatted][pem format] (Base-64)
certificate (if a certificate was presented) in a single URI-encoded string.
Note that URL encoding is not sufficient; all space characters must be
encoded as `%20` and not `+` characters.

> **Note:** Puppet Server only uses the value of this header to extract [trusted facts][trusted] from extensions in the client certificate. If you aren't using trusted facts, you can choose to reduce the size of the request payload by omitting the `X-Client-Cert` header.

> **Note:** Apache's `mod_proxy` converts line breaks in PEM documents to spaces for some reason, and Puppet Server can't decode the result. We're tracking this issue as [SERVER-217](https://tickets.puppetlabs.com/browse/SERVER-217).

[pem format]: https://docs.puppet.com/background/ssl/cert_anatomy.html#pem-file
[trusted]: https://puppet.com/docs/puppet/latest/lang_facts_and_builtin_vars.html#trusted-facts
