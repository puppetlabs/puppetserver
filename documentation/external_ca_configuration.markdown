---
layout: default
title: "Puppet Server: External CA Configuration"
canonical: "/puppetserver/latest/external_ca_configuration.html"
---


Puppet Server supports the ability to configure certificates from an existing
external CA. This is similar to Ruby Puppet master functionality under a Rack-enabled web server like Apache with Passenger. Much of the existing
documentation on [External CA Support for the Ruby Puppet Master](https://docs.puppetlabs.com/puppet/latest/reference/config_ssl_external_ca.html)
still applies to using an external CA with Puppet Server. However, there are some configuration differences with Puppet Server, which we've detailed on this page.

## Client DN Authentication

Puppet Server is hosted by a Jetty web server; therefore, Rack-enabled web server configuration is irrelevant. For client authentication purposes, Puppet Server can extract the distinguished name (DN) from a client certificate provided during SSL negotiation with the Jetty web server. This means the web server no longer needs to be configured to use an `X-Client-DN` request header for client authentication.

That said, the use of an `X-Client-DN` request header is still supported
for cases where SSL termination of client requests needs to be done on an
external server. See [External SSL Termination with Puppet Server](./external_ssl_termination.markdown) for details.

## Disabling the Internal Puppet CA Service

If you are using certs from an external CA, you'll need to disable the internal Puppet CA service. However, the `ca` setting from the `puppet.conf` file isn't honored by Puppet Server, so you'll disable the service in the `bootstrap.cfg` file (usually located in `/etc/puppetserver/bootstrap.cfg`).

To disable the Puppet CA service in `bootstrap.cfg`, comment out the line following "To enable the CA service..." and uncomment the line following "To disable the CA service...":

~~~
# To enable the CA service, leave the following line uncommented
# puppetlabs.services.ca.certificate-authority-service/certificate-authority-service
# To disable the CA service, comment out the above line and uncomment the line below
puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
~~~

For more information on the `bootstrap.cfg` file, see [Service Bootstrapping](./configuration.markdown#service-bootstrapping).

## Web Server Configuration

The [webserver.conf](./configuration.markdown#webserverconf) file for Puppet Server performs a function similar to that of VirtualHost configuration for
a Ruby Puppet master running on an Apache server. Several `ssl-` settings
should be added to the `webserver.conf` file to enable the web server to
use the correct SSL configuration:

* `ssl-cert`: The value of `puppet master --configprint hostcert`. Equivalent to the 'SSLCertificateFile' Apache config setting.
* `ssl-key`: The value of `puppet master --configprint hostprivkey`. Equivalent to the 'SSLCertificateKeyFile' Apache config setting.
* `ssl-ca-cert`: The value of `puppet master --configprint localcacert`. Equivalent to the 'SSLCACertificateFile' Apache config setting.
* `ssl-cert-chain`: Equivalent to the 'SSLCertificateChainFile' Apache config setting. Optional.
* `ssl-crl-path`: Equivalent to the 'SSLCARevocationPath' Apache config setting. Optional.

An example `webserver.conf` file might look something like this:

~~~
webserver: {

  client-auth : want
  ssl-host    : 0.0.0.0
  ssl-port    : 8140

  ssl-cert    : /path/to/master.pem

  ssl-key     : /path/to/master.key

  ssl-ca-cert : /path/to/ca_bundle.pem

  ssl-cert-chain : /path/to/ca_bundle.pem

  ssl-crl-path : /etc/puppetlabs/puppet/ssl/crl.pem
}
~~~

For more information on these settings, see [Configuring the Web Server Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md).

## Restart Required

After the above changes are made to Puppet Server's configuration files, you'll have to restart the Puppet Server service for the new settings to take effect.
