Puppet Server Configuration
=====

Puppet Server honors *almost* all settings in `puppet.conf`, and should pick them
up automatically.  It does introduce new settings for some "Puppet Server"-specific
things, though.  For example, the webserver interface and port are typically
configured in Puppet Server's `webserver.conf` file.

`conf.d` Config Files
-----

At start up, Puppet Server will read all of the `.conf` files found in the
`conf.d` directory (`/etc/puppetserver/conf.d` on most platforms).  Here are the
files that you will typically find in that directory, and a list of the settings
that they support.

*NOTE*: you will need to restart Puppet Server in order for changes to these
settings to take effect.

#### `global.conf`

This file contains global configuration for the application.  Here is an
example of the settings supported in this section:

```
global: {
  # Path to logback logging configuration file; for more
  # info, see http://logback.qos.ch/manual/configuration.html
  logging-config: /etc/puppetlabs/pe-puppetserver/logback.xml
}
```

#### `webserver.conf`

This file contains all of the settings to configure the web server.  For
full documentation, see
[Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md).

#### `puppetserver.conf`

This file contains settings related to Puppet Server itself:

```
# configuration for the JRuby interpreters
jruby-puppet: {
    # This setting determines where JRuby will look for gems.  It is also
    # used by the `puppetserver gem` command line tool.
    gem-home: /var/lib/puppet/jruby-gems

    # (optional) path to puppet conf dir; if not specified, will use the puppet default
    master-conf-dir: /etc/puppet

    # (optional) path to puppet var dir; if not specified, will use the puppet default
    master-var-dir: /var/lib/puppet

    # (optional) maximum number of JRuby instances to allow; defaults to <num-cpus>+2
    #max-active-instances: 1
}

# settings related to profiling the puppet Ruby code
profiler: {
    # enable or disable profiling for the Ruby code; defaults to 'false'.
    #enabled: true
}
```

#### `master.conf`

Contains options which change the behavior of the Puppet Master functionality
of Puppet Server.

```
master: {
    # Allows the `ssl_client_header` and `ssl_client_verify_header` options set
    # in puppet.conf to work. These headers will be ignored unless 
    # `allow-header-cert-info` is set to true.
    # allow-header-cert-info: true
}
```

#### `ca.conf`

This file contains settings related to the Certificate Authority service:

```
# CA-related settings
certificate-authority: {

    # settings for the certificate_status HTTP endpoint
    certificate-status: {
    
        # (optional) Whether a client certificate is required to access
        # the certificate status endpoints. A 'false' value will allow
        # plaintext access and the client-whitelist will be ignored.
        # Defaults to 'true'.
        authorization-required: true

        # this setting contains a list of client certnames who are whitelisted to
        # have access to the certificate_status endpoint.  Any requests made to
        # this endpoint that do not present a valid client cert mentioned in
        # this list will be denied access.
        client-whitelist: []
    }
}
```

#### `os-settings.conf`

This file should generally not be modified by users; it is set up by packaging
and is used to initialize the ruby load paths for JRuby.  It can be modified
to add additional paths to the JRuby load path:

```
os-settings: {
    ruby-load-paths: ["/usr/lib/ruby/site_ruby/1.8"]
}
```

Logging
-----

All of Puppet Server's logging is routed through the JVM [Logback](http://logback.qos.ch/)
library.  You may specify a custom `logback.xml` configuration file in the `global`
section of the Puppet Server settings mentioned above.  For more information on
configuring logback itself, see the [Logback Configuration Manual](http://logback.qos.ch/manual/configuration.html).

Service Bootstrapping
-----

Puppet Server is built on top of our open-source Clojure application framework,
[Trapperkeeper](https://github.com/puppetlabs/trapperkeeper).  One of the features
that Trapperkeeper provides is the ability to enable or disable individual
services that an application provides.  In Puppet Server, you may use this
feature to enable or disable the CA service, by modifying your `bootstrap.cfg` file
(usually located in `/etc/puppetserver/bootstrap.cfg`); in that file, you should
see some lines that look like this:

```
# To enable the CA service, leave the following line uncommented
puppetlabs.services.ca.certificate-authority-service/certificate-authority-service
# To disable the CA service, comment out the above line and uncomment the line below
#puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
```

In most cases you'll want the CA service enabled, but if you're running in
a multi-master environment or using an external CA, you may wish to disable
the CA service on some nodes.
