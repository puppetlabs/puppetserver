---
layout: default
title: "Puppet Server: Deprecated Features"
canonical: "/puppetserver/latest/deprecated_features.html"
---

[auth.conf]: https://puppet.com/docs/puppet/latest/config_file_auth.html

The following features / configuration settings are deprecated and will be
removed in a future major release of Puppet Server.

### Use of Core Puppet "auth.conf" for Authorizing Master Service Routes

#### Now

The value of the `jruby-puppet.use-legacy-auth-conf` setting in the
[puppetserver.conf](./configuration.markdown#puppetserverconf) file determines
which mechanism Puppet Server uses to authorize requests to the following
endpoints:

* [Puppet's HTTPS API (current)](https://puppet.com/docs/puppet/latest/http_api/http_api_index.html)
* [Puppet's HTTPS API (3.x)](https://github.com/puppetlabs/puppet/blob/3.8.0/api/docs/http_api_index.md)

For a value of `true`, the core
[Puppet auth.conf][auth.conf] file (`/etc/puppetlabs/puppet/auth.conf`), is
used when authorizing client requests.

For a value of `false` (also the default if not specified), Puppet Server uses the `authorization` settings in
its own "auth.conf" file, evaluated by the `trapperkeeper-authorization`
service. This "auth.conf" file is installed at
`/etc/puppetlabs/puppetserver/conf.d/auth.conf`. See the
[puppetserver "auth.conf"](./config_file_auth.markdown) page for more
information.

#### In a Future Major Release

The `jruby-puppet.use-legacy-auth-conf` setting will be removed from
Puppet Server configuration, and Puppet Server will instead always use
the new `trapperkeeper-authorization` "auth.conf" when authorizing client
requests.

#### Detecting and Updating

Look at the value of the `use-legacy-auth-conf` setting in the `jruby-puppet`
section of the "puppetserver.conf" file.  If the setting is not specified or
is set to `true`, you are using the deprecated core Puppet "auth.conf" for
authorization.

If you have not customized any of the rules in the core Puppet "auth.conf"
settings, you should just be able to set the `use-legacy-auth-conf` setting
to `false` and restart your puppetserver service in order for Puppet Server
to start using the `trapperkeeper-authorization` "auth.conf" file.

If you have customized rules in the core Puppet "auth.conf" file, you will need
to migrate your Puppet rule settings over to the `trapperkeeper-authorization`
"auth.conf" file.  See the
[puppetserver "auth.conf"](./config_file_auth.markdown) page for more
information.  You would then also need to set the `use-legacy-auth-conf`
setting to `false` and restart the puppetserver service.

#### Context

In previous Puppet Server releases, there was no unified mechanism for
controlling access to the various endpoints that Puppet Server hosts. Puppet
Server used core Puppet "auth.conf" to authorize requests handled by the master
service and custom client whitelists for the CA and Admin endpoints.

`trapperkeeper-authorization` unifies authorization configuration across all
of these endpoints into a single file. The newer "auth.conf" file also uses
the more flexible HOCON file format for compatibility with how Puppet Server
configuration files are handled by the Trapperkeeper framework.

### `certificate-status` settings

#### Now

If the `certificate-authority.certificate-status.authorization-required`
setting is `false`, all requests that are successfully validated by SSL (if
applicable for the port settings on the server) are permitted to use the
[Certificate Status](https://github.com/puppetlabs/puppet/blob/master/api/docs/http_certificate_status.md)
HTTP API endpoints.  This includes requests which do not provide an SSL
client certificate.

If the `certificate-authority.certificate-status.authorization-required` setting
is `true` or not specified and the `puppet-admin.client-whitelist` setting has
one or more entries, only the requests whose Common Name in the SSL client
certificate subject matches one of the `client-whitelist` entries are
permitted to use the certificate status HTTP API endpoints.

For any other configuration, requests are only permitted to access the
certificate status HTTP API endpoints if allowed per the rule definitions in
the `trapperkeeper-authorization` "auth.conf" file.  See the
[puppetserver "auth.conf"](./config_file_auth.markdown) page for more
information.

#### In a Future Major Release

The `certificate-status` settings will be ignored completely by Puppet Server.
Requests made to the `certificate-status` HTTP API will only be allowed per the
`trapperkeeper-authorization` "auth.conf" configuration.

#### Detecting and Updating

Look at the `certificate-status` settings in your configuration.  If
`authorization-required` is set to `false` or `client-whitelist` has one or
more entries, these settings would be used to authorize access to the
certificate status HTTP API instead of `trapperkeeper-authorization`.

If `authorization-required` is set to `true` or is not specified and if
the `client-whitelist` was empty, you could just remove the
`certificate-authority` section from your configuration.  The only behavior
that would change in Puppet Server from doing this would be that a warning
message would no longer be written to the "puppetserver.log" file at startup.

If `authorization-required` is set to `false`, you would need to create
a corresponding rule in the `trapperkeeper-authorization` file which would
allow unauthenticated client access to the certificate status API.

For example:

~~~~hocon
authorization: {
    version: 1
    rules: [
            {
                match-request: {
                    path: "/certificate_status/"
                    type: path
                    method: [ get, put, delete ]
                }
                allow-unauthenticated: true
                sort-order: 200
                name: "certificate_status"
            },
            {
                match-request: {
                    path: "/certificate_statuses/"
                    type: path
                    method: get
                }
                allow-unauthenticated: true
                sort-order: 200
                name: "certificate_statuses"
            },
            ...
    ]
}
~~~~

If `authorization-required` is set to `true` or not set but the
`client-whitelist` has one or more custom entries in it, you would need to
create a corresponding rule in the `trapperkeeper-authorization` "auth.conf"
file which would allow only specific clients access to the certificate status
API.

For example, the current certificate status configuration could have:

~~~~hocon
certificate-authority:
    certificate-status: {
        client-whitelist: [ admin1, admin2 ]
    }
}
~~~~

Corresponding `trapperkeeper-authorization` rules could have:

~~~~hocon
authorization: {
    version: 1
    rules: [
            {
                match-request: {
                    path: "/certificate_status/"
                    type: path
                    method: [ get, put, delete ]
                }
                allow: [ admin1, admin2 ]
                sort-order: 200
                name: "certificate_status"
            },
            {
                match-request: {
                    path: "/certificate_statuses/"
                    type: path
                    method: get
                }
                allow: [ admin1, admin2 ]
                sort-order: 200
                name: "certificate_statuses"
            },
            ...
    ]
}
~~~~

After adding the desired rules to the `trapperkeeper-authorization` "auth.conf"
file, remove the `certificate-authority` section from the "puppetserver.conf"
file and restart the puppetserver service.

#### Context

In previous Puppet Server releases, there was no unified mechanism for
controlling access to the various endpoints that Puppet Server hosts.  Puppet
Server used core Puppet "auth.conf" to authorize requests handled by the master
service and custom client whitelists for the CA and Admin endpoints.  The
custom client whitelists do not provide granular enough control to meet some
use cases.

`trapperkeeper-authorization` unifies authorization configuration across all
of these endpoints into a single file and provides more granular control.

### `puppet-admin` Settings

#### Now

If the `puppet-admin.authorization-required` setting is `false`, all requests
that are successfully validated by SSL (if applicable for the port settings
on the server) are permitted to use the `puppet-admin` HTTP API endpoints.
This includes requests which do not provide an SSL client certificate.

If the `puppet-admin.authorization-required` setting is `true` or not
specified and the `puppet-admin.client-whitelist` setting has one or more
entries, only the requests whose Common Name in the SSL client certificate
subject matches one of the `client-whitelist` entries are permitted to use
the `puppet-admin` HTTP API endpoints.

For any other configuration, requests are only permitted to access the
`puppet-admin` HTTP API endpoints if allowed per the rule definitions in the
`trapperkeeper-authorization` "auth.conf" file.  See the
[puppetserver "auth.conf"](./config_file_auth.markdown) page for more
information.

#### In a Future Major Release

The `puppet-admin` settings will be ignored completely by Puppet Server.
Requests made to the `puppet-admin` HTTP API will only be allowed per the
`trapperkeeper-authorization` "auth.conf" configuration.

#### Detecting and Updating

Look at the `puppet-admin` settings in your configuration.  If
`authorization-required` is set to `false` or `client-whitelist` has one or
more entries, these settings would be used to authorize access to the
`puppet-admin` HTTP API instead of `trapperkeeper-authorization`.

If `authorization-required` is set to `true` or is not specified and if
the `client-whitelist` was empty, you could just remove the `puppet-admin`
section from your configuration and restart your puppetserver service in order
for Puppet Server to start using the `trapperkeeper-authorization` "auth.conf"
file.  The only behavior that would change in Puppet Server from doing this
would be that a warning message would no longer be written to the
puppetserver.log file.

If `authorization-required` is set to `false`, you would need to create
corresponding rules in the `trapperkeeper-authorization` file which would
allow unauthenticated client access to the "puppet-admin" API endpoints.

For example:

~~~~hocon
authorization: {
    version: 1
    rules: [
            {
                match-request: {
                    path: "/puppet-admin-api/v1/environment-cache"
                    type: path
                    method: delete
                }
                allow-unauthenticated: true
                sort-order: 200
                name: "environment-cache"
            },
            {
                match-request: {
                    path: "/puppet-admin-api/v1/jruby-pool"
                    type: path
                    method: delete
                }
                allow-unauthenticated: true
                sort-order: 200
                name: "jruby-pool"
            },
            ...
     ]
}
~~~~

If `authorization-required` is set to `true` or not set but the
`client-whitelist` has one or more custom entries in it, you would need to
create corresponding rules in the `trapperkeeper-authorization` "auth.conf"
file which would allow only specific clients access to the "puppet-admin"
API endpoints.

For example, the current "puppet-admin" configuration could have:

~~~~hocon
puppet-admin: {
    client-whitelist: [ admin1, admin2 ]
}
~~~~

Corresponding `trapperkeeper-authorization` rules could have:

~~~~hocon
authorization: {
    version: 1
    rules: [
            {
                match-request: {
                    path: "/puppet-admin-api/v1/environment-cache"
                    type: path
                    method: delete
                }
                allow: [ admin1, admin2 ]
                sort-order: 200
                name: "environment-cache"
            },
            {
                match-request: {
                    path: "/puppet-admin-api/v1/jruby-pool"
                    type: path
                    method: delete
                }
                allow: [ admin1, admin2 ]
                sort-order: 200
                name: "jruby-pool"
            },
            ...
     ]
}
~~~~

After adding the desired rules to the `trapperkeeper-authorization` "auth.conf"
file, remove the `puppet-admin` section from the "puppetserver.conf" file
and restart the puppetserver service.

#### Context

In previous Puppet Server releases, there was no unified mechanism for
controlling access to the various endpoints that Puppet Server hosts.  Puppet
Server used core Puppet "auth.conf" to authorize requests handled by the master
service and custom client whitelists for the CA and Admin endpoints.  The
custom client whitelists do not provide granular enough control to meet some
use cases.

`trapperkeeper-authorization` unifies authorization configuration across all
of these endpoints into a single file and provides more granular control.

### Puppet's "resource_types" API endpoint

#### Now

The `resource_type` and `resource_types` HTTP APIs were removed in Puppet Server 5.0.

#### Previously

The [`resource_type` and `resource_types` Puppet HTTP API endpoints](https://puppet.com/docs/puppet/4.6/http_api/http_resource_type.html) return information about classes, defined types, and node definitions.

The [`environment_classes` HTTP API in Puppet Server](./puppet-api/v3/environment_classes.markdown) serves as a replacement for the Puppet resource type API for classes.

#### Detecting and Updating

If your application calls the `resource_type` or `resource_types` HTTP API endpoints for information about classes, point those calls to the `environment_classes` endpoint. The `environment_classes` endpoint has different features and returns different values than `resource_type`; see the [changes in the environment classes API](./puppet-api/v3/environment_classes.markdown) for details.

The `environment_classes` endpoint ignores Puppet's Ruby-based authorization methods and configuration in favor of Puppet Server's Trapperkeeper authorization. For more information, see the ["Authorization" section](./puppet-api/v3/environment_classes.markdown) of the environment classes API documentation.

#### Context

Users often rely on the `resource_types` endpoint for lists of classes and associated parameters in an environment. For such requests, the `resource_types` endpoint is inefficient and can trigger problematic events, such as [manifests being parsed during a catalog request](https://tickets.puppetlabs.com/browse/SERVER-1200).

To fulfill these requests more efficiently and safely, Puppet Server 2.3.0 introduced the narrowly defined `environment_classes` endpoint.

### Puppet's node cache terminus

#### Now

Puppet 5.0 (and by extension, Puppet Server 5.0) no longer writes node YAML files to its cache by default.

#### Previously

Puppet wrote YAML to its node cache.

#### Detecting and Updating

To retain the Puppet 4.x behavior, add the [`puppet.conf`](./configuration.markdown) setting `node_cache_terminus = write_only_yaml`. The `write_only_yaml` option is deprecated.

#### Context

This cache was used in workflows where external tooling needs a list of nodes. PuppetDB is the preferred source of node information.

### JRuby's "compat-version" setting

#### Now

Puppet Server 5.0 removes the `jruby-puppet.compat-version` setting in [`puppetserver.conf`](./config_file_puppetserver.markdown), and exits the `puppetserver` service with an error if you start the service with that setting.

#### Previously

Puppet Server 2.7.x allowed you to set `compat-version` to `1.9` or `2.0` to choose a preferred Ruby interpreter version.

#### Detecting and Updating

Launching the `puppetserver` service with this setting enabled will cause it to exit with an error message. The error includes information on [switching from JRuby 1.7.x to JRuby 9k](./configuration.markdown).

For Ruby language 2.x support in Puppet Server, configure Puppet Server to use JRuby 9k instead of JRuby 1.7.27. See the "Configuring the JRuby Version" section of [Puppet Server Configuration](./configuration.markdown) for details.

#### Context

Puppet Server 5.0 updated JRuby v1.7 to v1.7.27, which in turn updated the `jruby-openssl` gem to v0.9.19 and `bouncycastle` libraries to v1.55. JRuby 1.7.27 breaks setting `jruby-puppet.compat-version` to `2.0`.

Server 5.0 also added optional, experimental support for JRuby 9k, which includes Ruby 2.x language support.