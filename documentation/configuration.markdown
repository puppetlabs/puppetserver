---
layout: default
title: "Puppet Server Configuration"
canonical: "/puppetserver/latest/configuration.html"
---

[auth.conf]: https://puppet.com/docs/puppet/latest/config_file_auth.html
[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[`puppetserver.conf`]: ./config_file_puppetserver.markdown
[deprecated]: ./deprecated_features.markdown

Puppet Server honors most settings in `puppet.conf` and picks them up automatically. However, for some tasks, such as configuring the web server or an external Certificate Authority (CA), Puppet Server has separate configuration files and settings.

These files and settings are described below. For more information about differences between Puppet Server and the Ruby Puppet master's use of `puppet.conf` settings, see  [Puppet Server: Differing Behavior in `puppet.conf`](./puppet_conf_setting_diffs.markdown).

## Configuration Files

Puppet Server's configuration files and settings (with the exception of the [logging config file](#logging)) are in the `conf.d` directory, located by default at `/etc/puppetlabs/puppetserver/conf.d`. These config files are in the HOCON format, which keeps the basic structure of JSON but is more readable. For more information, see the [HOCON documentation](https://github.com/typesafehub/config/blob/master/HOCON.md).

At startup, Puppet Server reads all the `.conf` files in the `conf.d` directory. You must restart Puppet Server for any changes to those files to take effect. The `conf.d` directory contains the following files and settings:

* [`global.conf`](./config_file_global.markdown)
* [`webserver.conf`](./config_file_webserver.markdown)
* [`web-routes.conf`](./config_file_web-routes.markdown)
* [`puppetserver.conf`](./config_file_puppetserver.markdown)
* [`auth.conf`](./config_file_auth.markdown)
* [`ca.conf`](./config_file_ca.markdown)
* [`master.conf`](./config_file_master.markdown) ([deprecated][])

The [`product.conf`](./config_file_product.markdown) file is optional and is not included by default. You can create that file in the `conf.d` directory in order to configure product-related settings, such as automatic update checking and analytics data collection.

## Logging

Puppet Server's logging is routed through the JVM [Logback](http://logback.qos.ch/) library. The default Logback configuration file is at `/etc/puppetserver/logback.xml` or `/etc/puppetlabs/puppetserver/logback.xml`. You can edit this file to change the logging behavior, or specify a different Logback config file in [`global.conf`](#globalconf).

For more information on the `logback.xml` file, see [its documentation](./config_file_logbackxml.markdown) and the [Logback documentation](http://logback.qos.ch/manual/configuration.html). For advanced logging configuration tips, such as configuring Logstash or outputting logs in JSON format, see [the Advanced Logging Configuration guide](./config_logging_advanced.markdown).

For some tips on advanced logging configuration, including information about configuring your system to write logs in a JSON format suitable for sending to logstash or other external logging systems, see the [Advanced Logging Configuration](./config_logging_advanced.markdown) documentation.

### HTTP Traffic

Puppet Server logs HTTP traffic in a format similar to Apache, and to a separate file than the main log file. By default, this is located at `/var/log/puppetlabs/puppetserver/puppetserver-access.log`.

By default, the following information is logged for each HTTP request:

* remote host
* remote log name
* remote user
* date of the logging event
* URL requested
* status code of the request
* response content length
* remote IP address
* local port
* elapsed time to serve the request, in milliseconds

The Logback configuration file is at `/etc/puppetlabs/puppetserver/request-logging.xml`. You can edit this file to change the logging behavior. Specify a different Logback configuration file in [`webserver.conf`](#webserverconf) with the [`access-log-config`](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md#access-log-config) setting. For more information on configuring the logged data, see [Logback Access Pattern Layout](http://logback.qos.ch/manual/layouts.html#AccessPatternLayout).

### Authorization

To enable additional logging related to `auth.conf`, edit Puppet Server's
`logback.xml` file. By default, only a single message is logged when a request
is denied.

To enable a one-time logging of the parsed and transformed `auth.conf` file, add
the following to Puppet Server's `logback.xml` file:

~~~
<logger name="puppetlabs.trapperkeeper.services.authorization.authorization-service" level="DEBUG"/>
~~~

To enable rule-by-rule logging for each request as it's checked for
authorization, add the following to Puppet Server's `logback.xml` file:

~~~
<logger name="puppetlabs.trapperkeeper.authorization.rules" level="TRACE"/>
~~~

## Service Bootstrapping

Puppet Server is built on top of our open-source Clojure application framework, [Trapperkeeper](https://github.com/puppetlabs/trapperkeeper).

One of the features that Trapperkeeper provides is the ability to enable or disable individual services that an application provides. In Puppet Server, you can use this feature to enable or disable the CA service. The CA service is enabled by default, but if you're running a multi-master environment or using an external CA, you might want to disable the CA service on some nodes.

Starting in Puppet Server 2.5.0, the service bootstrap configuration files are in two locations:

-   `/etc/puppetlabs/puppetserver/services.d/`: For services that users are expected to manually configure if necessary, such as CA-related settings.
-   `/opt/puppetlabs/server/apps/puppetserver/config/services.d/`: For services users _shouldn't_ need to configure.

Any files with a `.cfg` extension in either of these locations are combined to form the final set of services Puppet Server will use.

The CA-related configuration settings are set in `/etc/puppetlabs/puppetserver/services.d/ca.cfg`. If services added in future versions have user-configurable settings, the configuration files will also be in this directory. When upgrading Puppet Server 2.5.0 and newer with a package manager, it should not overwrite files already in this directory.

> **Note:** If you're upgrading from Puppet Server 2.4.x or earlier to Server 2.5 or newer, read and act on the [bootstrap upgrade notes](./bootstrap_upgrade_notes.markdown) **before upgrading**.

In the `ca.cfg` file, find and modify these lines as directed to enable or disable the service:

~~~
# To enable the CA service, leave the following line uncommented
puppetlabs.services.ca.certificate-authority-service/certificate-authority-service
# To disable the CA service, comment out the above line and uncomment the line below
#puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
~~~

## Adding Java JARs

Starting with Puppet Server 5.1.0, you can provide Java JARs to be loaded upon Puppet Server's startup.

When launched, Server automatically loads any JARs placed in `/opt/puppetlabs/server/data/puppetserver/jars` into the `classpath`. JARs placed here will not be modified or removed when upgrading Puppet Server.

## Enabling the Insecure SSLv3 Protocol

Puppet Server usually cannot use SSLv3, because it is disabled by default at the JRE layer. (As of javase 7u75 / 1.7.0_u75. See the [7u75 Update Release Notes](http://www.oracle.com/technetwork/java/javase/7u75-relnotes-2389086.html) for more information.)

You should almost always leave SSLv3 disabled, because it is compromized by the [POODLE vulnerability](https://blogs.oracle.com/security/entry/information_about_ssl_poodle_vulnerability) and no longer secure. If you have clients that can't use newer protocols, you should try to upgrade them instead of downgrading Puppet Server.

However, if you absolutely must, you can allow Puppet Server to negotiate with SSLv3 clients.

To enable SSLv3 at the JRE layer, first create a properties file (for example, `/etc/sysconfig/puppetserver-properties/java.security`) with the following content:

~~~
# Override properties in $JAVA_HOME/jre/lib/security/java.security
# An empty value enables all algorithms including INSECURE SSLv3
# java should be started with
# -Djava.security.properties=/etc/sysconfig/puppetserver-properties/java.security
# for this file to take effect.
jdk.tls.disabledAlgorithms=
~~~

Once this property file exists, update `JAVA_ARGS`, typically defined in `/etc/sysconfig/puppetserver`, and add the option `-Djava.security.properties=/etc/sysconfig/puppetserver-properties/java.security`. This will configure the JVM to override the `jdk.tls.disabledAlgorithms` property defined in `$JAVA_HOME/jre/lib/security/java.security`. Restart the `puppetserver` service for this setting to take effect.
