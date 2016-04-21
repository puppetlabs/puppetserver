---
layout: default
title: "Puppet Server Configuration Files: webserver.conf"
canonical: "/puppetserver/latest/config_file_webserver.html"
---

The `webserver.conf` file configures the Puppet Server `webserver` service. For an overview, see [Puppet Server Configuration](./configuration.html). To configure the mount points for the Puppet administrative API web applications, see the [`web-routes.conf` documentation](./config_file_web-routes.md).

## Examples

The `webserver.conf` file looks something like this:

~~~
# Configure the webserver.
webserver: {
    # Log webserver access to a specific file.
    access-log-config = /etc/puppetlabs/puppetserver/request-logging.xml
    # Require a valid certificate from the client.
    client-auth = want
    # Listen for HTTPS traffic on all available hostnames.
    ssl-host = 0.0.0.0
    # Listen for HTTPS traffic on port 8140.
    ssl-port = 8140
}
~~~

These are the main values for managing a Puppet Server installation. For further documentation, including a complete list of available settings and values, see [Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md).

By default, Puppet Server is configured to use the correct Puppet master and certificate authority (CA) certificates. If you're using an external CA and providing your own certificates and keys, make sure the SSL-related parameters in `webserver.conf` point to the correct file. 

~~~
webserver: {
    ...
    ssl-cert    : /path/to/master.pem
    ssl-key     : /path/to/master.key
    ssl-ca-cert : /path/to/ca_bundle.pem
    ssl-cert-chain : /path/to/ca_bundle.pem
    ssl-crl-path : /etc/puppetlabs/puppet/ssl/crl.pem
}
~~~

Configuring an external CA requires additional steps, which are described in [External CA Configuration](./external_ca_configuration.markdown).
