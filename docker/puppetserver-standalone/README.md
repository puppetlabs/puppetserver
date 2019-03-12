# [puppetlabs/puppetserver-standalone](https://github.com/puppetlabs/puppetserver)

The Dockerfile for this image is available in the Puppetserver repository
[here][1].

You can run a copy of Puppet Server with the following Docker command:

    docker run --name puppet --hostname puppet puppet/puppetserver-standalone

Although it is not strictly necessary to name the container `puppet`, this is
useful when working with the other Puppet images, as they will look for a master
on that hostname by default.

If you would like to start the Puppet Server with your own Puppet code, you can
mount your own directory at `/etc/puppetlabs/code`:

    docker run --name puppet --hostname puppet -v ./code:/etc/puppetlabs/code/ puppet/puppetserver-standalone

You can find out more about Puppet Server in the [official documentation][2].

See the [pupperware repository][3] for running a full Puppet stack using Docker
Compose.

## Configuration

The following environment variables are supported:

- `PUPPERWARE_DISABLE_ANALYTICS`

  Set to `false` to disable Google Analytics.



[1]: https://github.com/puppetlabs/puppetserver/blob/master/docker/puppetserver-standalone/Dockerfile
[2]: https://puppet.com/docs/puppetserver/latest/services_master_puppetserver.html
[3]: https://github.com/puppetlabs/pupperware
