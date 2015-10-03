---
layout: default
title: "Puppet Server: Configuration"
canonical: "/puppetserver/latest/configuration.html"
---

[auth.conf]: /puppet/latest/reference/config_file_auth.html
[`trapperkeeper-authorization`]: https://github.com/puppetlabs/trapperkeeper-authorization
[`puppetserver.conf`]: ./configuration.html#puppetserverconf

Puppet Server honors almost all settings in `puppet.conf` and should pick them up automatically. However, for some tasks, such as configuring the webserver or an external Certificate Authority, we have introduced new Puppet Server-specific configuration files and settings. These new files and settings are detailed below.  For more information on the specific differences in Puppet Server's support for `puppet.conf` settings as compared to the Ruby master, see the [puppet.conf differences](./puppet_conf_setting_diffs.markdown) page.

## Config Files

All of Puppet Server's new config files and settings (with the exception of the [logging config file](#logging)) are located in the `conf.d` directory, located by default at `/etc/puppetlabs/puppetserver/conf.d`. These new config files are in the HOCON format, which keeps the basic structure of JSON but is more human-readable. For more information, see the [HOCON documentation](https://github.com/typesafehub/config/blob/master/HOCON.md).

At startup, Puppet Server reads all the `.conf` files in the `conf.d` directory. You must restart Puppet Server for any changes to those files to take effect. The `conf.d` directory contains the following files and settings:

### `global.conf`

This file contains global configuration settings for Puppet Server. You shouldn't typically need to make changes to this file. However, you can change the `logging-config` path for the logback logging configuration file if necessary. For more information about the logback file, see [http://logback.qos.ch/manual/configuration.html](http://logback.qos.ch/manual/configuration.html).

~~~
global: {
  logging-config: /etc/puppetlabs/puppetserver/logback.xml
}
~~~

### `webserver.conf`

This file contains the web server configuration settings. The `webserver.conf` file looks something like this:

~~~
webserver: {
    client-auth = need
    ssl-host = 0.0.0.0
    ssl-port = 8140
}

# configure the mount points for the web apps
web-router-service: {
    # These two should not be modified because the Puppet 3.x agent expects them to
    # be mounted at "/"
    "puppetlabs.services.ca.certificate-authority-service/certificate-authority-service": ""
    "puppetlabs.services.master.master-service/master-service": ""

    # This controls the mount point for the puppet admin API.
    "puppetlabs.services.puppet-admin.puppet-admin-service/puppet-admin-service": "/puppet-admin-api"
}
~~~

The above settings set the webserver to require a valid certificate from the client; to listen on all available hostnames for encrypted HTTPS traffic; and to use port 8140 for encrypted HTTPS traffic. For full documentation, including a complete list of available settings and values, see [Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md).

By default, Puppet Server is configured to use the correct Puppet Master and CA certificates. If you're using an external CA and have provided your own certificates and keys, make sure `webserver.conf` points to the correct file. For details about configuring an external CA, see the [External CA Configuration](./external_ca_configuration.markdown) page.

### `puppetserver.conf`

[configuration directory]: /puppet/latest/reference/dirs_confdir.html
[code directory]: /puppet/latest/reference/dirs_codedir.html
[cache directory]: /puppet/latest/reference/dirs_vardir.html

This file contains the settings for Puppet Server itself.

> **Note:** Most users should never set the `master-conf-dir` or `master-code-dir` settings to a non-default value. If you do, you must also change the equivalent Puppet settings (`confdir` or `codedir`) to ensure that commands like `puppet cert` and `puppet module` will use the same directories as Puppet Server. Note also that a non-default `confdir` must be specified on the command line when running commands, since that setting must be set before Puppet tries to find its config file.

*   The `jruby-puppet` settings configure the interpreter:
    *   `ruby-load-path`: Where the Puppet Server expects to find Puppet, Facter, etc.
    *   `gem-home`: This setting determines where JRuby looks for gems. It is
      also used by the `puppetserver gem` command line tool. If not specified,
      uses the Puppet default `/opt/puppetlabs/server/data/puppetserver/jruby-gems`.
    *   `master-conf-dir`: Optionally, set the path to the Puppet
      [configuration directory][]. If not specified, the default is `/etc/puppetlabs/puppet`.
    *   `master-code-dir`: Optionally, set the path to the Puppet [code directory][].
      If not specified, the default is `/etc/puppetlabs/code`.
    *   `master-var-dir`: Optionally, set the path to the Puppet [cache directory][].
      If not specified, the default is
      `/opt/puppetlabs/server/data/puppetserver`.
    *   `master-run-dir`: Optionally, set the path to the run directory, where the service's PID file is stored.
      If not specified, the default is `/var/run/puppetlabs/puppetserver`.
    *   `master-log-dir`: Optionally, set the path to the log directory.
      If not specified, uses the Puppet default `/var/log/puppetlabs/puppetserver`.
    *   `max-active-instances`: Optionally, set the maximum number of JRuby
      instances to allow. Defaults to 'num-cpus - 1', with a minimum default
      value of 1 and a maximum default value of 4.
    *   `max-requests-per-instance`: Optionally, limit how many HTTP requests a given JRuby instance will handle in its lifetime. When a JRuby instance reaches this limit, it gets flushed from memory and replaced with a fresh one. Defaults to 0, which disables automatic JRuby flushing.

        This can be useful for working around buggy module code that would otherwise cause memory leaks, but it causes a slight performance penalty whenever a new JRuby has to reload all of the Puppet Ruby code.  If memory leaks from module code are not an issue in your deployment, the default value will give the best performance.
    *   `borrow-timeout`: Optionally, set the timeout when attempting to borrow an instance from the JRuby pool in milliseconds. Defaults to 1200000.
    *   `use-legacy-auth-conf`: Optionally, set the method to be used for authorizing access to the HTTP endpoints served by the "master" service. The applicable endpoints include those listed in the [Puppet v3 HTTP API](/puppet/4.2/reference/http_api/http_api_index.html#puppet-v3-http-api).

      For a value of `true` or if this setting is not specified, Puppet uses the core Ruby `puppet-agent` code for authorization via the legacy [Puppet `auth.conf`][auth.conf] format, which is [deprecated][] and will be removed in a future version of Puppet Server.
      
      For a value of `false`, Puppet uses a new HOCON configuration file format and location. See the [`auth.conf`](#authconf) section below for more information.
*   The `profiler` settings configure profiling:
    *   `enabled`: If this is set to `true`, Puppet Server enables profiling for the Puppet Ruby code. Default: `false`.
*   The `puppet-admin` section configures the Puppet Server's administrative API. (This is a new API and isn't provided by Rack or WEBrick Puppet masters.) With the introduction of a new method for authorizing requests made to Puppet Server in Puppet Server 2.2, the settings in this section are now deprecated. You should consider removing these settings in favor of the new authorization method and `auth.conf` format and location. See the [`auth.conf`](#authconf) section for more information.
    *   `authorization-required` determines whether a client certificate is required to access the endpoints in this API. If set to `false`, all requests will be permitted to access this API. If set to `true`, only the clients whose certnames are included in the `client-whitelist` setting are allowed access to the admin API. If this setting is not specified but the `client-whitelist` setting is specified, the default value for this setting is `true`.
    *   `client-whitelist` contains an array of client certificate names that are whitelisted to access the admin API. Puppet Server denies any requests made to this endpoint that do not present a valid client certificate mentioned in this array.

   If neither the `authorization-required` nor the `client-whitelist` settings are specified, Puppet Server uses the new authorization methods and [`auth.conf`](#authconf) formats to access the admin API endpoints.

~~~
# configuration for the JRuby interpreters

jruby-puppet: {
    ruby-load-path: [/opt/puppetlabs/puppet/lib/ruby/vendor_ruby]
    gem-home: /opt/puppetlabs/server/data/puppetserver/jruby-gems
    master-conf-dir: /etc/puppetlabs/puppet
    master-code-dir: /etc/puppetlabs/code
    master-var-dir: /opt/puppetlabs/server/data/puppetserver
    master-run-dir: /var/run/puppetlabs/puppetserver
    master-log-dir: /var/log/puppetlabs/puppetserver
    max-active-instances: 1
    max-requests-per-instance: 0
    use-legacy-auth-conf: false
}

# settings related to HTTP client requests made by Puppet Server
http-client: {
    # A list of acceptable protocols for making HTTP requests
    #ssl-protocols: [TLSv1, TLSv1.1, TLSv1.2]

    # A list of acceptable cipher suites for making HTTP requests.  For more info on available cipher suites, see:
    # http://docs.oracle.com/javase/7/docs/technotes/guides/security/SunProviders.html#SunJSSEProvider
    #cipher-suites: [TLS_RSA_WITH_AES_256_CBC_SHA256,
    #                TLS_RSA_WITH_AES_256_CBC_SHA,
    #                TLS_RSA_WITH_AES_128_CBC_SHA256,
    #                TLS_RSA_WITH_AES_128_CBC_SHA]

    # The amount of time, in milliseconds, that an outbound HTTP connection
    # will wait for data to be available before closing the socket. If not
    # defined, defaults to 20 minutes. If 0, the timeout is infinite and if
    # negative, the value is undefined by the application and governed by the
    # system default behavior.
    #idle-timeout-milliseconds: 1200000

    # The amount of time, in milliseconds, that an outbound HTTP connection will
    # wait to connect before giving up. Defaults to 2 minutes if not set. If 0,
    # the timeout is infinite and if negative, the value is undefined in the
    # application and governed by the system default behavior.
    #connect-timeout-milliseconds: 120000
}

# settings related to profiling the puppet Ruby code
profiler: {
    enabled: true
}

# Settings related to the puppet-admin HTTP API - deprecated in favor
# of "auth.conf"
# puppet-admin: {
#    client-whitelist: []
# }
~~~

### `auth.conf`

This file, located by default at `/etc/puppetlabs/puppet/auth.conf`, contains rules for authorizing access to Puppet Server's HTTP API endpoints. As of version 2.2, Puppet Server can use [`trapperkeeper-authorization`][] for authentication, which is configured by a new HOCON `/etc/puppetlabs/puppet/auth.conf`. The core [Puppet `auth.conf`][auth.conf] authorization method and configuration file format is deprecated and will be removed in a future version of Puppet Server.

The new Puppet Server authentication basic configuration and functionality is similar to the legacy method: you define rules in `auth.conf`, and Puppet Server applies the settings when a request's endpoint matches a rule. However, the new HOCON format provides different parameters and syntax, and slightly different functionality, for rules than the legacy format.

#### Parameters

You can use the following parameters when writing or migrating custom rules.

##### `path`

For the `path` parameter, the legacy `auth.conf` format uses the presence of a tilde (`~`) character to distinguish a path representing a regular expression from a literal string that a matching endpoint must start with.

~~~~
# Regular expression to match a path in a URL.
path ~ ^/puppet/v3/report/([^/]+)$

# Literal string to match at the start of a URL's path.
path /puppet/v3/report/
~~~~

For HOCON `auth.conf` rules, distinguish between a regular expression and a literal string by explicitly stating the `type` attribute.

~~~~
# Regular expression to match a path in a URL.
path: "^/puppet/v3/report/([^/]+)$"
type: regex

# Literal string to match at the start of a URL's path.
path: "/puppet/v3/report/"
type: path
~~~~

> **Note:** Remember to delimit the contents of the `path` with double quotes for HOCON `auth.conf` rules. While the HOCON configuration format does not always require wrapping string values with double quotes, special characters commonly used in regular expressions (such as `*`) break HOCON parsing unless the entire value is surrounded by double quotes.

##### `method`

For the `method` parameter's value, the legacy `auth.conf` format uses indirector names in place of the request's HTTP method. For a complete translation of indirector names to HTTP methods, see the [Puppet `auth.conf` documentation](/puppet/latest/reference/config_file_auth.html#method).

~~~~
method: find
~~~~

For HOCON `auth.conf` rules, use HTTP methods instead of indicators in the parameter's value.

~~~~
method: [get, post]
~~~~

##### `environment`

For endpoints on a Puppet 4 master, you can supply the `environment` as a query parameter to the request's base URL. 

In the legacy Puppet `auth.conf` format, the `environment` parameter takes a comma-separated list and applies them as query parameters to the base URL.

~~~~
environment: production,test
~~~~

In the new HOCON format, use the `query-params` setting and provide the list as a HOCON array of the setting's `environment` parameter:

~~~~
query-params: {
    environment: [ production, test ]
}
~~~~

> **Note:** For Puppet 3 master endpoints, the `environment` was represented as the first subpath in the URL instead of as a query parameter. As noted in the [Puppet 3 agent compatibility section](#puppet-3-agent-compatibility), Puppet Server translates incoming Puppet 3-style URLs to Puppet 4-style URLs before evaluating them against the new HOCON `auth.conf` rules, so the `query-params` approach above would replace environment-specific rules both for Puppet 3 and Puppet 4.

##### `auth` (Legacy) and `allow-unauthenticated`

In the legacy Puppet `auth.conf` format, use the `auth` parameter to specify whether a rule applies only to authenticated clients, such as those that provide a client certificate (`on`), only to unauthenticated clients (`off`), or to both (`any`).

For example, this legacy `auth.conf` rule matches all clients, including those that do not have to be authenticated:

~~~~
auth: any
~~~~

If you enable the new authentication method, Puppet Server only evaluates whether to allow or deny requests _after_ a rule is matched. As such, there is no directly equivalent behavior to the legacy `auth` parameter. However, you can instruct Puppet Server to allow or deny requests from unauthenticated clients by using the Boolean `allow-unauthenticated` parameter:

~~~~
allow-unauthenticated: true
~~~~

> **Note:** The new authentication method introduced in Puppet Server 2.2 does not support the `allow_ip` or `deny_ip` parameters.

#### Puppet 3 agent compatibility

Puppet 4 changed the URL structure for Puppet master and CA endpoints. For more information, see:

*   [Puppet 4 HTTPS API documentation](/puppet/latest/reference/http_api/http_api_index.html)
*   [Puppet 3 HTTPS API documentation](https://github.com/puppetlabs/puppet/blob/3.8.0/api/docs/http_api_index.md)
*   [Puppet 4 `auth.conf` documentation](/puppet/latest/reference/config_file_auth.html)
*   [Puppet 3 `auth.conf` documentation](/puppet/3.8/reference/config_file_auth.html)

Puppet Server allows agents to make requests at the old URLs and internally translates them as requests to the new endpoints. However, rules in `auth.conf` that match Puppet 3-style URLs will have _no effect._ For more information, see the [Puppet agent compatibility](./compatibility_with_puppet_agent.markdown) documentation.

#### Related Configuration Settings

For backward compatibility, other [`puppetserver.conf`][] settings control specific endpoints for the new Puppet Server authorization method:

*   [`use-legacy-auth-conf` in the `jruby-puppet` section](#puppetserverconf): If `true`, use the legacy Ruby authorization methods and  [`auth.conf`](https://docs.puppetlabs.com/puppet/4.2/reference/config_file_auth.html) format. If `false`, use the new `trapperkeeper-authorization` authorization method and HOCON `auth.conf` format. Default: `true`.

*   `authorization-required` and `client-whitelist` in the `puppet-admin` section: If either of these settings is present in the configuration, requests made to
 Puppet Server's administrative API will be performed per the values for these
 settings.  See the [`puppetserver.conf/puppet-admin`](#puppetserverconf)
 section for more information on these settings.  If neither the
 `puppet-admin.authorization-required` nor the `puppet-admin.client-whitelist`
 setting is specified, requests to Puppet Server's administrative API will be
 done via `trapperkeeper-authorization`.

 * `certificate-authority.certificate-status.authorization-required` and
  `certificate-authority.certificate-status.client-whitelist` -
  If either of these settings is present in the configuration, requests made
  to Puppet Server's
  [Certificate Status](https://github.com/puppetlabs/puppet/blob/master/api/docs/http_certificate_status.md)
  API will be performed per the values for these settings.  See the
  [`ca.conf`](#caconf) section for more information on these settings.  If
  neither the `certificate-authority.certificate-status.authorization-required`
  nor the `certificate-authority.certificate-status.client-whitelist` setting is
  specified, requests to Puppet Server's administrative API will be done via
  `trapperkeeper-authorization`.

Support for the use of the legacy `auth.conf` for the "master" endpoints and
for the client whitelists for the Puppet admin and certificate status endpoints
is deprecated.  You should consider configuring the above settings such that
only `trapperkeeper-authorization` is used for authorizing requests.

### `master.conf`

This file contains settings for Puppet master features, such as node
identification and authorization.  The only setting that this file supports is
`allow-header-cert-info`.  That setting is now deprecated in favor of the
`authorization.allow-header-cert-info` setting in the `auth.conf` file that
`trapperkeeper-authorization` uses.  For more information on the `auth.conf`
file in general, see the [`auth.conf`](#authconf) section.  For more information
on the `authorization.allow-header-cert-info` setting, see the
[`Configuring the Authorization Service`](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md#allow-header-cert-info)
page.

In a default installation, this file doesn't exist.

* `allow-header-cert-info` determines whether Puppet Server should use authorization info from the `X-Client-Verify`, `X-Client-CN`, and `X-Client-Cert` HTTP headers. Defaults to `false`.

    This setting is used to enable [external SSL termination.](./external_ssl_termination.markdown) If enabled, Puppet Server will ignore any actual certificate presented to the Jetty webserver, and will rely completely on header data to authorize requests. This is very dangerous unless you've secured your network to prevent any untrusted access to Puppet Server.

    When the `master.allow-header-cert-info` setting is being used, you can
    change Puppet's `ssl_client_verify_header` setting to use another header
    name instead of `X-Client-Verify`; the `ssl_client_header` setting can
    rename `X-Client-CN`.  The `X-Client-Cert` header can't be renamed.  When
    the `authorization.allow-header-cert-info` setting is being used, however,
    none of the `X-Client` headers can be renamed; identity must be specified
    through the `X-Client-Verify`, `X-Client-CN`, and `X-Client-Cert` headers.
    
    Note that the `master.allow-header-cert-info` setting only applies to HTTP
    endpoints served by the "master" service.  The applicable endpoints include
    those listed in the
    [Puppet V3 HTTP API](https://docs.puppetlabs.com/puppet/4.2/reference/http_api/http_api_index.html#puppet-v3-http-api).
    The `master.allow-header-cert-info` setting does not apply to the endpoints
    listed in the
    [CA V1 HTTP API](https://docs.puppetlabs.com/puppet/4.2/reference/http_api/http_api_index.html#ca-v1-http-api)
    or to any of the [Puppet Admin API](#puppetserverconf) endpoints.

    The `authorization.allow-header-cert-info` setting, however, applies to all
    HTTP endpoints that Puppet Server handles - including ones served by the
    "master" service and the CA and Puppet Admin APIs.

    If `trapperkeeper-authorization` is enabled for authorizing requests to the
    "master" HTTP endpoints - via the
    [`jruby-puppet.use-legacy-auth-conf`](#puppetserverconf) setting being set
    to `false` - the value of the `authorization.allow-header-cert-info`
    setting controls how the user's identity is derived for authorization
    purposes.  In this case, the value of the `master.allow-header-cert-info`
    setting would be ignored.

~~~
# Deprecated in favor of `authorization.allow-header-cert-info` in "auth.conf"
# master: {
#   allow-header-cert-info: false
# }
~~~

#### HOCON `auth.conf` Examples

~~~
authorization: {
    version: 1
    # allow-header-cert-info: false
    rules: [
        {
            # Allow nodes to retrieve their own catalog
            match-request: {
                path: "^/puppet/v3/catalog/([^/]+)$"
                type: regex
                method: [get, post]
            }
            allow: "$1"
            sort-order: 500
            name: "puppetlabs catalog"
        },
        ...
    ]
}
~~~

For more information on this format, see
[the `trapperkeepr-authorization` documentation](https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md).

### `ca.conf`

This file contains settings for the Certificate Authority (CA) service.

> **Deprecation Note:** This file only supports the `authorization-required` and `client-whitelist` settings, which are deprecated as of Puppet Server 2.2 in favor of new authorization methods configured in the HOCON-based [`auth.conf`](#authconf) file. Since these settings are deprecated and the new authorization methods are enabled by default, Puppet Server no longer includes a default `ca.conf` file in the Puppet Server package.

The `certificate-status` setting in `ca.conf` provides legacy configuration options for access to the `certificate_status` and `certificate_statuses` HTTP endpoints. These endpoints allow certificates to be signed, revoked, and deleted via HTTP requests, which provides full control over Puppet's ability to securely authorize access; therefore, you should **always** restrict access to `ca.conf`. 

> **Puppet Enterprise Note:** Puppet Enterprise uses these endpoints to provide a console interface for certificate signing. For more information, see the [Certificate Status documentation](/puppet/latest/reference/http_api/http_certificate_status.html).

This setting takes two parameters: `authorization-required` and `client-whitelist`. If neither parameter is specified, or if the `client-whitelist` is specified but empty, Puppet Server uses the [new authorization methods][`trapperkeeper-authorization`] and [`auth.conf` format](#authconf) introduced in Puppet Server 2.2 to control access to certificate status endpoints.

*   `authorization-required` determines whether a client certificate is required to access certificate status endpoints. If this parameter is set to `false`, all requests can access this API. If set to `true`, only the clients whose certificate names are included in the `client-whitelist` setting can access the admin API. If this parameter is not specified but the `client-whitelist` parameter is, this parameter's value defaults to `true`.
*   `client-whitelist` contains a list of client certificate names that are whitelisted for access to the certificate status endpoints. Puppet Server denies access to requests at these endpoints that do not present a valid client certificate named in this list.

#### Example (Legacy)

If you are not using the new authorization methods, follow this structure to configure  `certificate_status` and `certificate_statuses` endpoint access in `ca.conf`:

~~~
# CA-related settings - deprecated in favor of "auth.conf"
certificate-authority: {
   certificate-status: {
       authorization-required: true
       client-whitelist: []
   }
}
~~~

## Logging

All of Puppet Server's logging is routed through the JVM [Logback](http://logback.qos.ch/) library. By default, it logs to `/var/log/puppetserver/puppetserver.log` (open source releases) or `/var/log/pe-puppetserver/puppetserver.log` (Puppet Enterprise). The default log level is 'INFO'. By default, Puppet Server sends nothing to syslog.

The default Logback configuration file is at `/etc/puppetserver/logback.xml` or `/etc/puppetlabs/puppetserver/logback.xml`. You can edit this file to change the logging behavior, and/or specify a different Logback config file in [`global.conf`](#globalconf). For more information on configuring Logback itself, see the [Logback Configuration Manual](http://logback.qos.ch/manual/configuration.html). Puppet Server picks up changes to logback.xml at runtime, so you don't need to restart the service for changes to take effect.

Puppet Server relies on `logrotate` to manage the log file, and installs a configuration file at `/etc/logrotate.d/puppetserver` or `/etc/logrotate.d/pe-puppetserver`.

### HTTP Traffic

Puppet Server logs HTTP traffic in a format similar to Apache, and to a separate
file than the main log file. By default, this is located at
`/var/log/puppetlabs/puppetserver/puppetserver-access.log` (open source releases) and
`/var/log/pe-puppetserver/puppetserver-access.log` (Puppet Enterprise).

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

The Logback configuration file is at
`/etc/puppetlabs/puppetserver/request-logging.xml`. You can edit this file to
change the logging behavior, and/or specify a different Logback configuration
file in [`webserver.conf`](#webserverconf) with the
[`access-log-config`](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md#access-log-config)
setting. For more information on configuring the logged data, see the
[Logback Access Pattern Layout](http://logback.qos.ch/manual/layouts.html#AccessPatternLayout).

## Service Bootstrapping

Puppet Server is built on top of our open-source Clojure application framework,
[Trapperkeeper](https://github.com/puppetlabs/trapperkeeper). One of the features
that Trapperkeeper provides is the ability to enable or disable individual
services that an application provides. In Puppet Server, you can use this
feature to enable or disable the CA service, by modifying your `bootstrap.cfg` file
(usually located in `/etc/puppetserver/bootstrap.cfg`); in that file, you should
see some lines that look like this:

~~~
# To enable the CA service, leave the following line uncommented
puppetlabs.services.ca.certificate-authority-service/certificate-authority-service
# To disable the CA service, comment out the above line and uncomment the line below
#puppetlabs.services.ca.certificate-authority-disabled-service/certificate-authority-disabled-service
~~~

In most cases, you'll want the CA service enabled. However, if you're running
a multi-master environment or using an external CA, you might want to disable
the CA service on some nodes.


## Enabling the Insecure SSLv3 Protocol

Puppet Server usually cannot use SSLv3, because it is disabled by default at the
JRE layer. (As of javase 7u75 / 1.7.0_u75. See the
[7u75 Update Release Notes](http://www.oracle.com/technetwork/java/javase/7u75-relnotes-2389086.html)
for more information.)

You should almost always leave SSLv3 disabled, because it isn't secure anymore;
it's been broken since the
[POODLE vulnerability.](https://blogs.oracle.com/security/entry/information_about_ssl_poodle_vulnerability)
If you have clients that can't use newer protocols, you should try to upgrade
them instead of downgrading Puppet Server.

However, if you absolutely must, you can allow Puppet Server to negotiate with
SSLv3 clients.

To enable SSLv3 at the JRE layer, first create a properties file (e.g.
`/etc/sysconfig/puppetserver-properties/java.security`) with the following
content:

~~~
# Override properties in $JAVA_HOME/jre/lib/security/java.security
# An empty value enables all algorithms including INSECURE SSLv3
# java should be started with
# -Djava.security.properties=/etc/sysconfig/puppetserver-properties/java.security
# for this file to take effect.
jdk.tls.disabledAlgorithms=
~~~

Once this property file exists, update `JAVA_ARGS`, typically defined in
`/etc/sysconfig/puppetserver`, and add the option
`-Djava.security.properties=/etc/sysconfig/puppetserver-properties/java.security`.  This
will configure the JVM to override the `jdk.tls.disabledAlgorithms` property
defined in `$JAVA_HOME/jre/lib/security/java.security`.  The `puppetserver`
service needs to be restarted for this setting to take effect.
