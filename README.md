# Puppet Server

[Puppet Server](./documentation/services_master_puppetserver.markdown) is the
next-generation application for managing
[Puppet](https://puppet.com/docs/puppet/) agents. This platform implements
Puppet's server-side components in a more distributed, service-oriented
architecture. We've built Puppet Server on top of the same technologies that
make [PuppetDB](https://puppet.com/docs/puppetdb/) successful, and which
allow us to greatly improve performance, scalability, advanced metrics
collection, and fine-grained control over the Ruby runtime.

While Puppet Server is designed to replace the
[deprecated](https://puppet.com/docs/puppet/latest/deprecated_servers.html)
[Apache/Passenger Puppet master
stack](https://puppet.com/docs/puppet/latest/services_master_rack.html),
they diverge in a handful of ways due to differences in Puppet Server's
underlying architecture. See [Puppet Server vs. Apache/Passenger Puppet
Master](./documentation/puppetserver_vs_passenger.markdown) for details.

## Release notes

For information about the current and most recent versions of Puppet Server,
see the [release notes](./documentation/release_notes.markdown).

## Installing Puppet Server

All versions of Puppet Server depend on at least Puppet 3.7.3, and since
version 2.3 it depends on [Puppet Agent
1.4.0](https://docs.puppet.com/puppet/4.4/about_agent.html) or newer,
which installs [Puppet 4.4](https://docs.puppet.com/puppet/4.4/) and compatible
versions of its related tools and dependencies on the server. Puppet agents
running older versions of Puppet Agent can connect to Puppet Server 2.3;
this requirement applies to the Puppet Agent running on the Puppet Server node
*only*.

If you install Puppet Server on a system running an older version of Puppet,
installation also upgrades Puppet. See [Installing Puppet Server from
Packages](./documentation/install_from_packages.markdown) for complete
installation requirements and instructions.

## Ruby and Puppet Server

Puppet Server uses its own JRuby interpreter, which doesn't load gems or other
code from your system Ruby. If you want Puppet Server to load additional gems,
use the Puppet Server-specific `gem` command to install them. See [Puppet
Server and Gems](./documentation/gems.markdown) for more information about gems
and Puppet Server.

## Configuration

Puppet Server honors almost all settings in `puppet.conf` and should pick them
up automatically. However, we have also introduced some new settings specific
to Puppet Server. See the [Configuration](./documentation/configuration.markdown)
documentation for details.

For more information on the differences between Puppet Server's support for
`puppet.conf` settings and the Ruby master's, see our documentation of
[differences in
`puppet.conf`](./documentation/puppet_conf_setting_diffs.markdown).

### Certificate authority configuration

Much of the documentation on [External CA Support for the Ruby Puppet
Master](https://puppet.com/docs/puppet/latest/config_ssl_external_ca.html)
still applies to using an external certificate authority in conjunction
with Puppet Server. There are some differences to bear in mind, however; see the
[External CA Configuration](./documentation/external_ca_configuration.markdown)
page for details.

### SSL configuration

In network configurations that require external SSL termination, you need to do
a few things differently in Puppet Server. See
[External SSL Termination](./documentation/external_ssl_termination.markdown)
for details.

## Command-line utilities

Puppet Server provides several command-line utilities for development and
debugging purposes. These commands are all aware of
[`puppetserver.conf`](./documentation/config_file_puppetserver.markdown), as
well as the gems and Ruby code specific to Puppet Server and Puppet, while
keeping them isolated from your system Ruby.

For more information, see [Puppet Server
Subcommands](./documentation/subcommands.markdown).

## Known issues

As this application is still in development, there are a few [known
issues](./documentation/known_issues.markdown) that you should be aware of.

## Developer documentation

If want to play with our code, these documents should prove useful:

-   [Running Puppet Server from
    source](./documentation/dev_running_from_source.markdown)
-   [Debugging](./documentation/dev_debugging.markdown)
-   [Puppet Server subcommands](./documentation/subcommands.markdown)

Puppet Server also uses the
[Trapperkeeper](https://github.com/puppetlabs/trapperkeeper) Clojure framework.

## Branching strategy

Puppet Server's branching strategy is documented on the [GitHub repo
wiki](https://github.com/puppetlabs/puppetserver/wiki/Branching-Strategy).

## Issue tracker

Have feature requests, found a bug, or want to see what issues are in flight?
Visit our [JIRA project](https://tickets.puppetlabs.com/browse/SERVER).

## License

Copyright © 2013---2018 Puppet

Distributed under the [Apache License, Version
2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

## Special thanks to

### Cursive Clojure

[Cursive](https://cursiveclojure.com/) is a Clojure IDE based on [IntelliJ
IDEA](http://www.jetbrains.com/idea/download/index.html). Several of us at
Puppet use it regularly and couldn't live without it. It's got some really great
editing, refactoring, and debugging features, and the author, Colin Fleming, has
been amazingly helpful and responsive when we have feedback. If you're a Clojure
developer, you should definitely check it out!

### JRuby

[JRuby](http://jruby.org/) is an implementation of the Ruby programming language
that runs on the JVM. It's a fantastic project, and the bridge that allows us to
run Puppet Ruby code while taking advantage of the JVM's advanced features and
libraries. We're very grateful to the developers for building such a great
product and for helping us work through a few bugs that we've discovered along
the way.

## Maintenance

Maintainers: See the [MAINTAINERS file](./MAINTAINERS)

Tickets: For issues in o/s only: https://tickets.puppetlabs.com/browse/SERVER. For issues in PE: https://tickets.puppetlabs.com/browse/PE. Set component = Puppet Server
