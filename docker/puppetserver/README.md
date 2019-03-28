# [puppetlabs/puppetserver](https://github.com/puppetlabs/puppetserver)

The Dockerfile for this image is available in the Puppetserver repository
[here][1].

You can run a copy of Puppet Server with the following Docker command:

    docker run --name puppet --hostname puppet puppet/puppetserver

Although it is not strictly necessary to name the container `puppet`, this is
useful when working with the other Puppet images, as they will look for a master
on that hostname by default.

If you would like to start the Puppet Server with your own Puppet code, you can
mount your own directory at `/etc/puppetlabs/code`.

    docker run --name puppet --hostname puppet -v ./code:/etc/puppetlabs/code/ puppet/puppetserver

Note that this container will send reports to a PuppetDB instance on
`https://puppetdb`. Puppet runs will not fail by default if PuppetDB cannot be
reached. If you are not using PuppetDB, then the
`puppet/puppetserver-standalone` container may be a better choice. You can find
out more about Puppet Server in the [official documentation][2].

See the [pupperware repository][3] for how to run a full Puppet stack using
Docker Compose.

## Configuration

The following environment variables are supported:

- `PUPPERWARE_DISABLE_ANALYTICS`

  Set to `true` to disable Google Analytics. Defaults to `true`.

- `PUPPETSERVER_JAVA_ARGS`

  Additional Java args to pass to the puppetserver process. Defaults to '-Xms512m -Xmx512m'.

- `PUPPET_HEALTHCHECK_ENVIRONMENT`

  The environment to get the status of in the healthcheck script. Defaults to 'production'.

- `PUPPETSERVER_MAX_ACTIVE_INSTANCES`

  The maximum number of JRuby instances allowed. Defaults to '1'.

- `PUPPETSERVER_MAX_REQUESTS_PER_INSTANCE`

  The maximume number HTTP requests a JRuby instance will handle in its lifetime. Defaults to '0' (Disable instance flushing).

- `CA_ENABLED`

  Whether or not this puppetserver instance has a running CA (Certificate Authority). Defaults to 'true'.

- `CA_HOSTNAME`

  The hostname for the puppetserver running the CA. Does nothing unless `CA_ENABLED=false`. Defaults to 'puppet'.

- `CONSUL_ENABLED`

  Whether or not to register the `puppet` service with an external consul server. Defaults to 'false'.

- `CONSUL_HOSTNAME`

  If consul is enabled, the hostname for the external consul server. Defaults to 'consul'.

- `CONSUL_PORT`

  If consul is enabled, the port to access consul at. Defaults to '8500'.

- `PUPPETDB_SERVER_URLS`

  The `server_urls` to set in /etc/puppetlabs/puppet/puppetdb.conf. Defaults to 'https://puppetdb:8081'.

- `PUPPETSERVER_HOSTNAME`

  The hostname for the puppetserver instance. This sets the `certname` and `server` in puppet.conf. Defaults to unset.

- `AUTOSIGN`

  Whether or not to enable autosigning on the puppetserver instance. Valid values match [true|false|/path/to/autosign.conf]. Defaults to 'true'.

- `DNS_ALT_NAMES`

  Alternate names to set in the puppetserver config and to be used in puppetserver certificate generation. Defaults to unset.

  **Note** this is only effective on the initial run of the container when certificates are generated.

## Analytics Data Collection

The puppetserver container collects usage data. This is disabled by default. You can enable it by passing `--env PUPPERWARE_DISABLE_ANALYTICS=false`
to your `docker run` command.

### What data is collected?
* Version of the puppetserver container.
* Anonymized IP address is used by Google Analytics for Geolocation data, but the IP address is not collected.

### Why does the puppetserver container collect data?

We collect data to help us understand how the containers are used and make decisions about upcoming changes.

### How can I opt out of puppetserver container data collection?

This is disabled by default.

[1]: https://github.com/puppetlabs/puppetserver/blob/master/docker/puppetserver/Dockerfile
[2]: https://puppet.com/docs/puppetserver/latest/services_master_puppetserver.html
[3]: https://github.com/puppetlabs/pupperware
