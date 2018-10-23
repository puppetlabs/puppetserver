---
layout: default
title: "How Puppet Server uses the values in puppet.conf"
canonical: "/puppetserver/latest/puppet_conf_setting_diffs.html"
---

Puppet Server honors almost all settings in puppet.conf and should pick them
up automatically. For more complete information on puppet.conf settings, see our [Configuration Reference](https://puppet.com/docs/puppet/latest/configuration.html) page.

### [`autoflush`](https://puppet.com/docs/puppet/latest/configuration.html#autoflush)

Puppet Server does not use this setting. For more information on the master
logging implementation for Puppet Server, see the [logging configuration section](./configuration.markdown#logging).

### [`bindaddress`](https://puppet.com/docs/puppet/latest/configuration.html#bindaddress)

Puppet Server does not use this setting. To set the address on which the master
listens, use either `host` (unencrypted) or `ssl-host` (SSL encrypted) in the
[webserver.conf](./configuration.markdown#webserverconf) file.

### [`ca`](https://puppet.com/docs/puppet/latest/configuration.html#ca)

Puppet Server does not use this setting. Instead, Puppet Server acts as a
certificate authority based on the certificate authority service configuration
in the `ca.cfg` file. See [Service Bootstrapping](./configuration.markdown#service-bootstrapping) for more details.

### [`ca_ttl`](https://puppet.com/docs/puppet/latest/configuration.html#cattl)

Puppet Server enforces a max ttl of 50 standard years (up to 1576800000 seconds).

### [`cacert`](https://puppet.com/docs/puppet/latest/configuration.html#cacert)

If you enable Puppet Server's certificate authority service, it uses the `cacert`
setting in puppet.conf to determine the location of the CA certificate for such
tasks as generating the CA certificate or using the CA to sign client certificates.
This is true regardless of the configuration of the `ssl-` settings in
[webserver.conf](./configuration.markdown#webserverconf).

### [`cacrl`](https://puppet.com/docs/puppet/latest/configuration.html#cacrl)

If you define `ssl-cert`, `ssl-key`, `ssl-ca-cert`, or `ssl-crl-path` in
[webserver.conf](./configuration.markdown#webserverconf), Puppet Server uses the
file at `ssl-crl-path` as the CRL for authenticating clients via SSL. If at least
one of the `ssl-` settings in webserver.conf is set but `ssl-crl-path` is not set,
Puppet Server will *not* use a CRL to validate clients via SSL.

If none of the `ssl-` settings in webserver.conf are set, Puppet Server uses
the CRL file defined for the `hostcrl` setting---and not the file defined for
the `cacrl` setting--in puppet.conf. At start time, Puppet Server copies the
file for the `cacrl` setting, if one exists, over to the location in the
`hostcrl` setting.

Any CRL file updates from the Puppet Server certificate authority---such as
revocations performed via the `certificate_status` HTTP endpoint---use the `cacrl`
setting in puppet.conf to determine the location of the CRL. This is true
regardless of the `ssl-` settings in webserver.conf.

### [`capass`](https://puppet.com/docs/puppet/latest/configuration.html#capass)

Puppet Server does not use this setting. Puppet Server's certificate authority
does not create a `capass` password file when the CA certificate and key are
generated.

### [`caprivatedir`](https://puppet.com/docs/puppet/latest/configuration.html#caprivatedir)

Puppet Server does not use this setting. Puppet Server's certificate authority
does not create this directory.

### [`daemonize`](https://puppet.com/docs/puppet/latest/configuration.html#daemonize)

Puppet Server does not use this setting.

### [`hostcert`](https://puppet.com/docs/puppet/latest/configuration.html#hostcert)

If you define `ssl-cert`, `ssl-key`, `ssl-ca-cert`, or `ssl-crl-path` in
[webserver.conf](./configuration.markdown#webserverconf), Puppet Server presents the file at `ssl-cert` to clients as the server certificate via
SSL.

If at least one of the `ssl-` settings in webserver.conf is set but
`ssl-cert` is not set, Puppet Server gives an error and shuts down at
startup. If none of the `ssl-` settings in webserver.conf are set, Puppet Server uses the file for the `hostcert` setting in puppet.conf as the server
certificate during SSL negotiation.

Regardless of the configuration of the `ssl-` "webserver.conf" settings, Puppet
Server's certificate authority service, if enabled, uses the `hostcert`
"puppet.conf" setting, and not the `ssl-cert` setting, to determine the
location of the server host certificate to generate.

### [`hostcrl`](https://puppet.com/docs/puppet/latest/configuration.html#hostcrl)

If you define `ssl-cert`, `ssl-key`, `ssl-ca-cert`, or `ssl-crl-path` in
[webserver.conf](./configuration.markdown#webserverconf), Puppet Server uses the
file at `ssl-crl-path` as the CRL for authenticating clients via SSL. If at least
one of the `ssl-` settings in webserver.conf is set but `ssl-crl-path` is not set,
Puppet Server will *not* use a CRL to validate clients via SSL.

If none of the `ssl-` settings in webserver.conf are set, Puppet Server uses
the CRL file defined for the `hostcrl` setting---and not the file defined for
the `cacrl` setting--in puppet.conf. At start time, Puppet Server copies the
file for the `cacrl` setting, if one exists, over to the location in the
`hostcrl` setting.

Any CRL file updates from the Puppet Server certificate authority---such as
revocations performed via the `certificate_status` HTTP endpoint---use the `cacrl`
setting in puppet.conf to determine the location of the CRL. This is true
regardless of the `ssl-` settings in webserver.conf.

### [`hostprivkey`](https://puppet.com/docs/puppet/latest/configuration.html#hostprivkey)

If you define `ssl-cert`, `ssl-key`, `ssl-ca-cert`, or `ssl-crl-path` in
[webserver.conf](./configuration.markdown#webserverconf), Puppet Server uses the file at `ssl-key` as the server private key during SSL transactions.

If at least one of the `ssl-` settings in webserver.conf is set but `ssl-key`
is not, Puppet Server gives an error and shuts down at startup. If none of
the `ssl-` settings in webserver.conf are set, Puppet Server uses the file for
the `hostprivkey` setting in puppet.conf as the server private key during SSL
negotiation.

If you enable the Puppet Server certificate authority service, Puppet Server uses the `hostprivkey` setting in puppet.conf to determine the location of the server host private key to generate. This is true regardless of the configuration of the `ssl-` settings in webserver.conf.

### [`http_debug`](https://puppet.com/docs/puppet/latest/configuration.html#httpdebug)

Puppet Server does not use this setting. Debugging for HTTP client code in
the Puppet Server master is controlled through Puppet Server's common logging
mechanism. For more information on the master logging implementation for Puppet
Server, see the [logging configuration section](./configuration.markdown#logging).

### [`keylength`](https://puppet.com/docs/puppet/latest/configuration.html#keylength)

Puppet Server does not currently use this setting. Puppet Server's certificate
authority generates 4096-bit keys in conjunction with any SSL certificates that
it generates.

### [`localcacert`](https://puppet.com/docs/puppet/latest/configuration.html#localcacert)

If you define `ssl-cert`, `ssl-key`, `ssl-ca-cert`, and/or `ssl-crl-path` in
[webserver.conf](./configuration.markdown#webserverconf), Puppet Server uses the
file at `ssl-ca-cert` as the CA cert store for authenticating clients via SSL.

If at least one of the `ssl-` settings in webserver.conf
is set but `ssl-ca-cert` is not set, Puppet Server gives an error
and shuts down at startup. If none of the `ssl-` settings in webserver.conf is
set, Puppet Server uses the CA file defined for the `localcacert` setting in
puppet.conf for SSL authentication.

### [`logdir`](https://puppet.com/docs/puppet/latest/configuration.html#logdir)

Puppet Server does not use this setting. For more information on the master
logging implementation for Puppet Server, see the [logging configuration section](./configuration.markdown#logging).

### [`masterhttplog`](https://puppet.com/docs/puppet/latest/configuration.html#masterhttplog)

Puppet Server does not use this setting. You can configure a web server access log via the `access-log-config` setting in the [webserver.conf](./configuration.markdown#webserverconf) file.

### [`masterlog`](https://puppet.com/docs/puppet/latest/configuration.html#masterlog)

Puppet Server does not use this setting. For more information on the master
logging implementation for Puppet Server, see the [logging configuration section](./configuration.markdown#logging).

### [`masterport`](https://puppet.com/docs/puppet/latest/configuration.html#masterport)

Puppet Server does not use this setting. To set the port on which the master listens, set the `port` (unencrypted) or `ssl-port` (SSL encrypted) setting in the
[webserver.conf](./configuration.markdown#webserverconf) file.

### [`puppetdlog`](https://puppet.com/docs/puppet/latest/configuration.html#puppetdlog)

Puppet Server does not use this setting. For more information on the master
logging implementation for Puppet Server, see the [logging configuration section](./configuration.markdown#logging).

### [`rails_loglevel`](https://puppet.com/docs/puppet/latest/configuration.html#railsloglevel)

Puppet Server does not use this setting.

### [`railslog`](https://puppet.com/docs/puppet/latest/configuration.html#railslog)

Puppet Server does not use this setting.

### [`ssl_client_header`](https://puppet.com/docs/puppet/latest/configuration.html#sslclientheader)

Puppet Server honors this setting only if the `allow-header-cert-info`
setting in the `master.conf` file is set to 'true'. For more information on
this setting, see the documentation on [external SSL termination](./external_ssl_termination.markdown).

###  [`ssl_client_verify_header`](https://puppet.com/docs/puppet/latest/configuration.html#sslclientverifyheader)

Puppet Server honors this setting only if the `allow-header-cert-info`
setting in the `master.conf` file is set to `true`. For more information on
this setting, see the documentation on [external SSL termination](./external_ssl_termination.markdown).

### [`ssl_server_ca_auth`](https://puppet.com/docs/puppet/latest/configuration.html#sslservercaauth)

Puppet Server does not use this setting. It only considers the `ssl-ca-cert`
setting from the webserver.conf file and the `cacert` setting from the
puppet.conf file. See [`cacert`](#cacert) for more information.

### [`syslogfacility`](https://puppet.com/docs/puppet/latest/configuration.html#syslogfacility)

Puppet Server does not use this setting.

### [`user`](https://puppet.com/docs/puppet/latest/configuration.html#user)

Puppet Server does not use this setting.

## HttpPool-Related Server Settings

### [`configtimeout`](https://puppet.com/docs/puppet/latest/configuration.html#configtimeout)

Puppet Server does not currently consider this setting for any code running on the master and using the `Puppet::Network::HttpPool` module to create an HTTP client connection. This pertains, for example, to any requests that the master would make to the `reporturl` for the `http` report processor. Note that Puppet agents do still honor this setting.

### [`http_proxy_host`](https://puppet.com/docs/puppet/latest/configuration.html#httpproxyhost)

Puppet Server does not currently consider this setting for any code running on the master and using the `Puppet::Network::HttpPool` module to create an HTTP client connection. This pertains, for example, to any requests that the master would make to the `reporturl` for the `http` report processor. Note that Puppet agents do still honor this setting.

### [`http_proxy_port`](https://puppet.com/docs/puppet/latest/configuration.html#httpproxyport)

Puppet Server does not currently consider this setting for any code running on the master and using the `Puppet::Network::HttpPool` module to create an HTTP client connection. This pertains, for example, to any requests that the master would make to the `reporturl` for the `http` report processor. Note that Puppet agents do still honor this setting.

## Overriding Puppet settings in Puppet Server

Currently, the [`jruby-puppet` section of your `puppetserver.conf` file](./configuration.markdown#puppetserver.conf) contains five settings
(`master-conf-dir`, `master-code-dir`, `master-var-dir`, `master-run-dir`, and `master-log-dir`) that allow you to override settings set in
your `puppet.conf` file. On installation, these five settings will be set to the proper default values.

While you are free to change these settings at will, please note that any changes made to the `master-conf-dir` and `master-code-dir` settings
absolutely MUST be made to the corresponding Puppet settings (`confdir` and `codedir`) as well to ensure that Puppet Server and the Puppet
cli tools (such as `puppetserver ca` and `puppet module`) use the same directories. The `master-conf-dir` and `master-code-dir`
settings apply to Puppet Server only, and will be ignored by the ruby code that runs when the Puppet CLI tools are run.

For example, say you have the `codedir` setting left unset in your `puppet.conf` file, and you change the `master-code-dir` setting to
`/etc/my-puppet-code-dir`. In this case, Puppet Server will read code from `/etc/my-puppet-code-dir`, but the `puppet module` tool will
think that your code is stored in `/etc/puppetlabs/code`.

While it is not as critical to keep `master-var-dir`, `master-run-dir`, and `master-log-dir` in sync with the `vardir`, `rundir`, and `logdir`
Puppet settings, please note that this applies to these settings as well.

Also, please note that these configuration differences also apply to the interpolation of the `confdir`, `codedir`, `vardir`, `rundir`, and `logdir`
settings in your `puppet.conf` file. So, take the above example, wherein you set `master-code-dir` to `/etc/my-puppet-code-dir`. Since the
`basemodulepath` setting is by default `$codedir/modules:/opt/puppetlabs/puppet/modules`, then Puppet Server would use
`/etc/my-puppet-code-dir/modules:/opt/puppetlabs/puppet/modules` for the value of the `basemodulepath` setting, whereas the `puppet module` tool would use
`/etc/puppetlabs/code/modules:/opt/puppetlabs/puppet/modules` for the value of the `basemodulepath` setting.
