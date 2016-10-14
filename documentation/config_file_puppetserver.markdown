---
layout: default
title: "Puppet Server Configuration Files: puppetserver.conf"
canonical: "/puppetserver/latest/config_file_puppetserver.html"
---

[configuration directory]: https://docs.puppet.com/puppet/latest/reference/dirs_confdir.html
[code directory]: https://docs.puppet.com/puppet/latest/reference/dirs_codedir.html
[cache directory]: https://docs.puppet.com/puppet/latest/reference/dirs_vardir.html
[`auth.conf` documentation]: ./config_file_auth.markdown
[`environment_classes` API documentation]: ./puppet-api/v3/environment_classes.markdown
[deprecated]: ./deprecated_features.markdown
[static catalogs]: https://docs.puppet.com/puppet/latest/reference/static_catalogs.html
[file resource]: https://docs.puppet.com/puppet/latest/reference/type.html#file
[`static_file_content`]: ./puppet-api/v3/static_file_content.markdown

The `puppetserver.conf` file contains settings for Puppet Server software. For an overview, see [Puppet Server Configuration](./configuration.markdown).

## Settings

> **Note:** Under most conditions, you won't change the default settings for `master-conf-dir` or `master-code-dir`. However, if you do, also change the equivalent Puppet settings (`confdir` or `codedir`) to ensure that commands like `puppet cert` and `puppet module` use the same directories as Puppet Server. You must also specify the non-default `confdir` when running commands, since that setting must be set before Puppet tries to find its config file.

* The `jruby-puppet` settings configure the interpreter:

    * `ruby-load-path`: The location where Puppet Server expects to find Puppet, Facter, and other components.

    * `gem-home`: The location where JRuby looks for gems. It is also used by the `puppetserver gem` command line tool. If nothing is specified, JRuby uses the Puppet default `/opt/puppetlabs/server/data/puppetserver/jruby-gems`.

    * `gem-path`: The complete "GEM_PATH" for jruby.  If set, it should include the `gem-home` directory, as well as any other directories that gems can be loaded from (including the vendored gems directory for gems that ship with puppetserver).  The default value is  `/opt/puppetlabs/server/data/puppetserver/jruby-gems:/opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems`.

    * `environment-vars:` Optional. A map of environment variables which are made visible to Ruby code running within JRuby, for example, via the Ruby `ENV` class.

        By default, the only environment variables whose values are set into JRuby from the shell are `HOME` and `PATH`.

        The default value for the `GEM_HOME` environment variable in JRuby is set from the value provided for the `jruby-puppet.gem-home` key.

        Any variable set from the map for the `environment-vars` key overrides these defaults. Avoid overriding `HOME`, `PATH`, or `GEM_HOME` here because these values are already configurable via the shell or `jruby-puppet.gem-home`.

    * `master-conf-dir`: Optional. The path to the Puppet [configuration directory][]. The default is `/etc/puppetlabs/puppet`.

    * `master-code-dir`: Optional. The path to the Puppet [code directory][]. The default is `/etc/puppetlabs/code`.

    * `master-var-dir`: Optional. The path to the Puppet [cache directory][]. The default is `/opt/puppetlabs/server/data/puppetserver`.

    * `master-run-dir`: Optional. The path to the run directory, where the service's PID file is stored. The default is `/var/run/puppetlabs/puppetserver`.

    * `master-log-dir`: Optional. The path to the log directory. If nothing is specified, it uses the Puppet default `/var/log/puppetlabs/puppetserver`.

    * `max-active-instances`: Optional. The maximum number of JRuby instances allowed. The default is 'num-cpus - 1', with a minimum value of 1 and a maximum value of 4.

    * `max-requests-per-instance`: Optional. The number of HTTP requests a given JRuby instance will handle in its lifetime. When a JRuby instance reaches this limit, it is flushed from memory and replaced with a fresh one. The default is 0, which disables automatic JRuby flushing.

        JRuby flushing can be useful for working around buggy module code that would otherwise cause memory leaks, but it slightly reduces performance whenever a new JRuby instance reloads all of the Puppet Ruby code. If memory leaks from module code are not an issue in your deployment, the default value of 0 performs best.

    * `borrow-timeout`: Optional. The timeout in milliseconds, when attempting to borrow an instance from the JRuby pool. The default is 1200000.

    * `use-legacy-auth-conf`: Optional. The method to be used for authorizing access to the HTTP endpoints served by the master service. The applicable endpoints are listed in [Puppet v3 HTTP API](https://docs.puppet.com/puppet/latest/reference/http_api/http_api_index.html#puppet-v3-http-api).

        If this setting is set to `true` or is not specified, Puppet uses the [deprecated][] Ruby `puppet-agent` authorization method and [Puppet `auth.conf`][`auth.conf` documentation] format, which will be removed in a future version of Puppet Server.

        For a value of `false`, Puppet uses the HOCON configuration file format and location.

        For more information, see the [`auth.conf` documentation][].

    * `environment-class-cache-enabled`: Optional. Used to control whether the master service maintains a cache in conjunction with the use of the [`environment_classes` API](./puppet-api/v3/environment_classes.markdown).

        If this setting is set to `true`, Puppet Server maintains the cache. It also returns an Etag header for each GET request to the API. For subsequent GET requests that use the prior Etag value in an If-None-Match header, when the class information available for an environment has not changed, Puppet Server returns an HTTP 304 (Not Modified) response with no body.

        If this setting is set to `false` or is not specified, Puppet Server doesn't maintain a cache, an Etag header is not returned for GET requests, and the If-None-Match header for an incoming request is ignored. It therefore parses the latest available code for an environment from disk on every incoming request.

        For more information, see the [`environment_classes` API documentation][].

    * `compile-mode`: Optional, experimental. Used to control JRuby's "CompileMode", which may improve performance. The default value is `off`, which is the most conservative value. A value of `jit` enables JRuby's "just-in-time" compilation of Ruby code. A value of `force` causes JRuby to attempt to pre-compile all Ruby code.

* The `profiler` settings configure profiling:

    * `enabled`: If this is set to `true`, Puppet Server enables profiling for the Puppet Ruby code. The default is `false`.

* The `puppet-admin` section configures Puppet Server's administrative API. (This API is unavailable with Rack or WEBrick Puppet masters.)

    > **Note:** The `puppet-admin` setting and `client-whitelist` parameter are deprecated in favor of authorization methods introduced in Puppet Server 2.2. For details, see the [`auth.conf` documentation][].

    * `authorization-required` determines whether a client certificate is required to access the endpoints in this API. If set to `false`, all requests will be permitted to access this API. If set to `true`, only the clients whose certnames are included in the `client-whitelist` setting are allowed access to the admin API. If this setting is not specified but the `client-whitelist` setting is specified, the default value for this setting is `true`.

    * `client-whitelist` contains an array of client certificate names that are allowed to access the admin API. Puppet Server denies any requests made to this endpoint that do not present a valid client certificate mentioned in this array.

    If neither the `authorization-required` nor the `client-whitelist` settings are specified, Puppet Server uses the new authorization methods and [`auth.conf`][`auth.conf` documentation] formats to access the admin API endpoints.

* The `versioned-code` settings configure commands required to use [static catalogs][]:

    * `code-id-command`: the path to an executable script that Puppet Server invokes to generate a `code_id`. When compiling a static catalog, Puppet Server uses the output of this script as the catalog's `code_id`. The `code_id` associates the catalog with the compile-time version of any [file resources][file resource] that has a `source` attribute with a `puppet:///` URI value.

    * `code-content-command` contains the path to an executable script that Puppet Server invokes when an agent makes a [`static_file_content`][] API request for the contents of a [file resource][] that has a `source` attribute with a `puppet:///` URI value.

> **Note:** The Puppet Server process must be able to execute the `code-id-command` and `code-content-command` scripts, and the scripts must return valid content to standard output and an error code of 0. For more information, see the [static catalogs][] and [`static_file_content` API][`static_file_content`] documentation.
>
> If you're using static catalogs, you **must** set and use **both** `code-id-command` and `code-content-command`. If only one of those settings are specified, Puppet Server fails to start. If neither setting is specified, Puppet Server defaults to generating catalogs without static features even when an agent requests a static catalog, which the agent will process as a normal catalog.

### Examples

``` hocon
# Configuration for the JRuby interpreters.

jruby-puppet: {
    ruby-load-path: [/opt/puppetlabs/puppet/lib/ruby/vendor_ruby]
    gem-home: /opt/puppetlabs/server/data/puppetserver/jruby-gems
    gem-home: /opt/puppetlabs/server/data/puppetserver/jruby-gems:/opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems
    environment-vars: { "FOO" : ${FOO}
                        "LANG" : "de_DE.UTF-8" }
    master-conf-dir: /etc/puppetlabs/puppet
    master-code-dir: /etc/puppetlabs/code
    master-var-dir: /opt/puppetlabs/server/data/puppetserver
    master-run-dir: /var/run/puppetlabs/puppetserver
    master-log-dir: /var/log/puppetlabs/puppetserver
    max-active-instances: 1
    max-requests-per-instance: 0
    use-legacy-auth-conf: false
}

# Settings related to HTTP client requests made by Puppet Server.
http-client: {
    # A list of acceptable protocols for making HTTP requests
    #ssl-protocols: [TLSv1, TLSv1.1, TLSv1.2]

    # A list of acceptable cipher suites for making HTTP requests. For more info on available cipher suites, see:
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

# Settings related to profiling the puppet Ruby code.
profiler: {
    enabled: true
}

# Settings related to static catalogs. These paths are examples. There are no default
# scripts provided with Puppet Server, and no default path for the scripts. To use static catalog features, you must set
# the paths and provide your own scripts.
versioned-code: {
    code-id-command: /opt/puppetlabs/server/apps/puppetserver/code-id-command_script.sh
    code-content-command: /opt/puppetlabs/server/apps/puppetserver/code-content-command_script.sh
}
```
