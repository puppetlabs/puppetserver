Puppet Config File Differences
=====

Puppet Server honors *almost* all settings in `puppet.conf`, and should pick them
up automatically.  This document outlines the settings for which there are
differences between Puppet Server as compared to a Ruby Puppet master's support
for `puppet.conf` settings.  For more complete information on `puppet.conf`
settings, see [this page]
(https://docs.puppetlabs.com/references/latest/configuration.html).

#### [autoflush] (https://docs.puppetlabs.com/references/latest/configuration.html#autoflush)

Puppet Server does not use this setting.  For more information on the master
logging implementation for Puppet Server, see the [logging configuration section]
(./configuration.markdown#logging).

#### [bindaddress] (https://docs.puppetlabs.com/references/latest/configuration.html#bindaddress)

Puppet Server does not use this setting.  The address on which the master
listens is set as the `host` (unencrypted) or `ssl-host` (SSL encrypted) in the
[webserver.conf] (./configuration.markdown#webserverconf) file.

#### [ca] (https://docs.puppetlabs.com/references/latest/configuration.html#ca)

Puppet Server does not use this setting.  Instead, Puppet Server will act as a
certificate authority based on the certificate authority service configuration
in the `bootstrap.cfg` file.  See [Service Bootstrapping]
(./configuration.markdown#service-bootstrapping) for more details.

#### [cacert] (https://docs.puppetlabs.com/references/latest/configuration.html#cacert)

If `ssl-cert`, `ssl-key`, `ssl-ca-cert`, and/or `ssl-crl-path` is defined in
[webserver.conf] (./configuration.markdown#webserverconf), the file at
`ssl-ca-cert` is what Puppet Server will use as the CA cert store for
authenticating clients via SSL.  If at least one of the `ssl-` "webserver.conf"
settings is set but `ssl-ca-cert` is not, Puppet Server will output an error
and shut down at startup.  If none of the `ssl-` "webserver.conf" settings is
set, Puppet Server will use the CA file defined for the `cacert` "puppet.conf"
setting for SSL authentication.

Regardless of the configuration of the `ssl-` "webserver.conf" settings, Puppet
Server's certificate authority service, if enabled, uses the `cacert`
"puppet.conf" setting, and not the `ssl-ca-cert` setting, to determine the
location of the CA certificate - e.g., when generating the CA certificate and
using the CA certificate to sign client certificates.

#### [cacrl] (https://docs.puppetlabs.com/references/latest/configuration.html#cacrl)

If `ssl-cert`, `ssl-key`, `ssl-ca-cert`, and/or `ssl-crl-path` is defined in
[webserver.conf] (./configuration.markdown#webserverconf), the file at
`ssl-crl-path` is what Puppet Server will use as the CRL for authenticating
clients via SSL.  If at least one of the `ssl-` "webserver.conf" settings is set
but `ssl-crl-path` is not, Puppet Server will not use a CRL to validate
clients via SSL.

If none of the `ssl-` "webserver.conf" settings is set, Puppet Server will use
the CRL file defined for the `cacrl` "puppet.conf" setting.  A Ruby Puppet
master hosted on the WEBrick web server would use the `cacrl` setting as the CRL
if the "puppet.conf" `ca` setting were "true" or the `hostcrl` setting if `ca`
setting were "false".  Puppet Server, however, ignores both the `ca` and
`hostcrl` setting from the "puppet.conf" file.

Regardless of the configuration of the `ssl-` "webserver.conf" settings, any
updates that that the Puppet Server certificate authority does to a CRL file,
e.g., revocations performed via the "certificate_status" HTTP endpoint, will
use the `cacrl` "puppet.conf" setting to determine the location of the CRL, not
the`ssl-crl-path` "webserver.conf" setting.

#### [capass] (https://docs.puppetlabs.com/references/latest/configuration.html#capass)

Puppet Server does not use this setting.  Puppet Server's certificate authority
does not create a `capass` password file when the CA certificate and key are
generated.

#### [caprivatedir] (https://docs.puppetlabs.com/references/latest/configuration.html#caprivatedir)

Puppet Server does not use this setting.  Puppet Server's certificate authority
does not create this directory nor put a `capass` file in it.

#### [daemonize] (https://docs.puppetlabs.com/references/latest/configuration.html#daemonize)

Puppet Server does not use this setting.

#### [hostcert] (https://docs.puppetlabs.com/references/latest/configuration.html#hostcert)

If `ssl-cert`, `ssl-key`, `ssl-ca-cert`, and/or `ssl-crl-path` is defined in
[webserver.conf] (./configuration.markdown#webserverconf), the file at `ssl-cert`
is what Puppet Server will present to clients as the server certificate via
SSL.  If at least one of the `ssl-` "webserver.conf" settings is set but
`ssl-cert` is not, Puppet Server will output an error and shut down at
startup.  If none of the `ssl-` "webserver.conf" settings is set, Puppet Server
will use the file for the `hostcert` "puppet.conf" setting as the server
certificate during SSL negotiation.

Regardless of the configuration of the `ssl-` "webserver.conf" settings, Puppet
Server's certificate authority service, if enabled, uses the `hostcert`
"puppet.conf" setting, and not the `ssl-cert` setting, to determine the
location of the server host certificate to generate.

#### [hostcrl] (https://docs.puppetlabs.com/references/latest/configuration.html#hostcrl)

Puppet Server does not use this setting.  See [cacrl] (#cacrl) for more details.

#### [hostprivkey] (https://docs.puppetlabs.com/references/latest/configuration.html#hostprivkey)

If `ssl-cert`, `ssl-key`, `ssl-ca-cert`, and/or `ssl-crl-path` is defined in
[webserver.conf] (./configuration.markdown#webserverconf), the file at `ssl-key`
is what Puppet Server will use as the server private key during SSL transactions.
If at least one of the `ssl-` "webserver.conf" settings is set but `ssl-key`
is not, Puppet Server will output an error and shut down at startup.  If none of
the `ssl-` "webserver.conf" settings is set, Puppet Server will use the file for
the `hostprivkey` "puppet.conf" setting as the server private key during SSL
negotiation.

Regardless of the configuration of the `ssl-` "webserver.conf" settings, Puppet
Server's certificate authority service, if enabled, uses the `hostprivkey`
"puppet.conf" setting, and not the `ssl-key` setting, to determine the
location of the server host private key to generate.

#### [http_debug] (https://docs.puppetlabs.com/references/latest/configuration.html#httpdebug)

Puppet Server does not use this setting.  Debugging for HTTP client code in
the Puppet Server master is controlled through Puppet Server's common logging
mechanism.  For more information on the master logging implementation for Puppet
Server, see the [logging configuration section]
(./configuration.markdown#logging).

#### [keylength] (https://docs.puppetlabs.com/references/latest/configuration.html#keylength)

Puppet Server does not currently use this setting.  Puppet Server's certificate
authority generates 4096 bit keys in conjunction with any SSL certificates that
it generates.

#### [logdir] (https://docs.puppetlabs.com/references/latest/configuration.html#logdir)

Puppet Server does not use this setting.  For more information on the master
logging implementation for Puppet Server, see the [logging configuration section]
(./configuration.markdown#logging).

#### [masterhttplog] (https://docs.puppetlabs.com/references/latest/configuration.html#masterhttplog)

Puppet Server does not use this setting.  A web server access log can be
configured via the `access-log-config` setting in the [webserver.conf]
(./configuration.markdown#webserverconf) file.

#### [masterlog] (https://docs.puppetlabs.com/references/latest/configuration.html#masterlog)

Puppet Server does not use this setting.  For more information on the master
logging implementation for Puppet Server, see the [logging configuration section]
(./configuration.markdown#logging).

#### [masterport] (https://docs.puppetlabs.com/references/latest/configuration.html#masterport)

Puppet Server does not use this setting.  The port on which the master listens
is set as the `port` (unencrypted) or `ssl-port` (SSL encrypted) setting in the
[webserver.conf] (./configuration.markdown#webserverconf) file.

#### [puppetdlog] (https://docs.puppetlabs.com/references/latest/configuration.html#puppetdlog)

Puppet Server does not use this setting.  For more information on the master
logging implementation for Puppet Server, see the [logging configuration section]
(./configuration.markdown#logging).

#### [rails_loglevel] (https://docs.puppetlabs.com/references/latest/configuration.html#railsloglevel)

Puppet Server does not use this setting.

#### [railslog] (https://docs.puppetlabs.com/references/latest/configuration.html#railslog)

Puppet Server does not use this setting.

#### [ssl_client_header] (https://docs.puppetlabs.com/references/latest/configuration.html#sslclientheader)

Puppet Server will honor this setting only if the `allow-header-cert-info`
setting in the `master.conf` file is set to `true`.  For more information on
this setting, see the documentation on [external SSL termination]
(./external_ssl_termination.markdown).

#### [ssl_client_verify_header] (https://docs.puppetlabs.com/references/latest/configuration.html#sslclientverifyheader)

Puppet Server will honor this setting only if the `allow-header-cert-info`
setting in the `master.conf` file is set to `true`.  For more information on
this setting, see the documentation on [external SSL termination]
(./external_ssl_termination.markdown).

#### [ssl_server_ca_auth] (https://docs.puppetlabs.com/references/latest/configuration.html#sslservercaauth)

Puppet Server does not use this setting.  It only considers the `ssl-ca-cert`
setting from the "webserver.conf" file and the `cacert` setting from the
"puppet.conf" file.  See [cacert] (#cacert) for more information.

#### [syslogfacility] (https://docs.puppetlabs.com/references/latest/configuration.html#syslogfacility)

Puppet Server does not use this setting.

#### [user] (https://docs.puppetlabs.com/references/latest/configuration.html#user)

Puppet Server does not use this setting.

Settings Maybe Not Worth Documenting?
=====

#### [configtimeout] (https://docs.puppetlabs.com/references/latest/configuration.html#configtimeout)

In the context of any code running on the master which uses the
`Puppet::Network::HttpPool` module to create an HTTP client connection, Puppet
Server does not currently consider this setting.  This pertains, for example, to
any requests that the master would make to the `reporturl` for the `http` report
processor.  Note that Puppet agents do still honor this setting.

#### [http_proxy_host] (https://docs.puppetlabs.com/references/latest/configuration.html#httpproxyhost)

In the context of any code running on the master which uses the
`Puppet::Network::HttpPool` module to create an HTTP client connection, Puppet
Server does not currently consider this setting.  This pertains, for example, to
any requests that the master would make to the `reporturl` for the `http` report
processor.  Note that Puppet agents do still honor this setting.

#### [http_proxy_port] (https://docs.puppetlabs.com/references/latest/configuration.html#httpproxyport)

In the context of any code running on the master which uses the
`Puppet::Network::HttpPool` module to create an HTTP client connection, Puppet
Server does not currently consider this setting.  This pertains, for example, to
any requests that the master would make to the `reporturl` for the `http` report
processor.  Note that Puppet agents do still honor this setting.