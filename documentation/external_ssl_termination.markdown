# External SSL termination with Puppet Server

In network configurations that require external SSL termination, there are some important differences between configuring the Apache/Passenger stack and configuring Puppet Server. 

Delivery of the SSL certificate to Puppet is the biggest difference between Ruby Puppet and Puppet Server. Under Apache/Passenger, the client's certificate is delivered via an environment variable passed between Apache and the Ruby Puppet process. This is not an option in Puppet Server; instead, the certificate must be passed to Puppet Server via an HTTP header. The header is `X-Client-Cert` and must contain the client's PEM-formatted (Base-64) certificate in a single URI-encoded string. Note that URL encoding is not sufficient; all space characters must be encoded as `%20` and not `+` characters. 

You'll need to turn off SSL and have Puppet Server use the HTTP protocol instead: remove the `ssl-port` and `ssl-host` settings from the `conf.d/webserver.conf` file and replace them with `port` and `host` settings. See [Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md) for more information on configuring the web server service.

To allow Puppet Server to recognize the HTTP headers described, open `config.d/master.conf` and add  `allow-header-cert-info: true` to the `webserver` config block. See [Puppet Server Configuration](./configuration.markdown) for more information on the `master.conf` file. Without `allow-header-cert-info` set to 'true', none of these HTTP headers are recognized by Puppet Server. 

You'll need to reboot Puppet Server for the changes to take effect. The `ssl_client_header` and `ssl_client_verify_header` options in the `puppet.conf` file will then be enabled and work exactly as documented in Ruby Puppet. Please see the [Puppet Documentation](https://docs.puppetlabs.com/references/3.7.latest/configuration.html#sslclientheader) for more detail on these. 

> **Warning**: Setting `allow-header-cert-info` to 'true' puts Puppet Server in an incredibly vulnerable state. Take extra caution to ensure it is **absolutely not reachable** by an untrusted network.
    
With `allow-header-cert-info` set to 'true', core Ruby Puppet application code will use only the client HTTP header values---not an SSL-layer client certificate---to determine the client subject name, authentication status, and certificate. This is true even if the web server is hosting an HTTPS connection. This applies to validation of the client via rules in the [auth.conf](https://docs.puppetlabs.com/guides/rest_auth_conf.html) file and any [trusted facts](https://docs.puppetlabs.com/puppet/latest/reference/lang_facts_and_builtin_vars.html#trusted-facts) extracted from certificate extensions.

If the `client-auth` setting in the `webserver` config block is set to `need` or `want`, the Jetty web server will validate the client certificate against a certificate authority store. Only the SSL-layer client certificate---not a certificate in an  `X-Client-Cert` header---will be validated against the certificate authority store.
    
