Puppet Server External CA Configuration
=====

Puppet Server supports the ability to configure certificates from an existing
external CA, similar to how a Ruby Puppet master does when running under a
Rack-enabled web server like Apache with Passenger.  Much of the existing
documentation on [External CA Support for the Ruby Puppet Master]
(https://docs.puppetlabs.com/puppet/latest/reference/config_ssl_external_ca.html)
still applies to using an external CA in conjunction with Puppet Server.  This
page covers the configuration differences from the existing documentation
which are unique to Puppet Server.

Client DN Authentication
----

Puppet Server is hosted by a Jetty webserver and, therefore, configuration
related to a Rack-enabled web server is irrelevant.  For client authentication
purposes, Puppet Server can extract the distinguished name (DN) from a client
certificate -- whether from an external CA or a Puppet CA -- provided during SSL
negotiation with the Jetty webserver.  For this reason, it is no longer strictly
required to configure the webserver to use an `X-Client-DN` request header for client
authentication.  The use of an `X-Client-DN` request header is still supported
for cases where SSL termination of client request needs to be done on an
external server.  See [External SSL Termination with Puppet Server]
(./external_ssl_termination.markdown) for details.

Disabling the Internal Puppet CA Service
----

The internal Puppet CA service should be disabled.  The `ca` setting from the
`puppet.conf` file is not honored by Puppet Server.  In order to disable the
Puppet CA service, the line following "enable the CA service" in the
`bootstrap.cfg` file (usually located in `/etc/puppetserver/bootstrap.cfg`)
should be commented out and the line following "disable the CA service" should
be uncommented:

```
# To enable the CA service, leave the following line uncommented
# puppetlabs.services.ca.certificate-authority-service/certificate-authority-service
# To disable the CA service, comment out the above line and uncomment the line below
puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
```

For more information on the `bootstrap.cfg` file, see [Service Bootstrapping]
(./configuration.markdown#service-bootstrapping).

Webserver Configuration
----

The [webserver.conf](./configuration.markdown#webserverconf) file serves a
similar function for Puppet Server as what a VirtualHost configuration does for
a Ruby Puppet master running on an Apache server.  Several `ssl-` settings
should be added to the `webserver.conf` file in order to enable the webserver to
use the correct SSL configuration.  Here is an example `webserver.conf` file:

```
webserver: {

  client-auth : want
  ssl-host    : 0.0.0.0
  ssl-port    : 8140

  # Replace with the value of `puppet master --configprint hostcert`
  # Equivalent to 'SSLCertificateFile' Apache config setting
  ssl-cert    : /path/to/master.pem

  # Replace with the value of `puppet master --configprint hostprivkey`
  # Equivalent to 'SSLCertificateKeyFile' Apache config setting
  ssl-key     : /path/to/master.key

  # Replace with the value of `puppet master --configprint localcacert`
  # Equivalent to 'SSLCACertificateFile' Apache config setting
  ssl-ca-cert : /path/to/ca_bundle.pem

  # Optional, equivalent to 'SSLCertificateChainFile' Apache config setting
  ssl-cert-chain : /path/to/ca_bundle.pem

  # Optional, equivalent to 'SSLCARevocationPath' Apache config setting
  ssl-crl-path : /etc/puppetlabs/puppet/ssl/crl.pem
}
```

For more information on these settings, see [Configuring the Webserver Service]
(https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md).

Restart Required
----

After the above changes are made to Puppet Server's configuration files, a
restart of the Puppet Server service is needed in order for the new settings
to take effect.
