# Puppet Server

[Puppet Server](https://puppet.com/docs/puppet/latest/server/about_server.html)
implements Puppet's server-side components for managing
[Puppet](https://puppet.com/docs/puppet/) agents in a distributed,
service-oriented architecture. Puppet Server is built on top of the same
technologies that make [PuppetDB](https://puppet.com/docs/puppetdb/)
successful, and which allow us to greatly improve performance, scalability,
advanced metrics collection, and fine-grained control over the Ruby runtime.

## Release notes

For information about the current and most recent versions of Puppet Server,
see the [release notes](https://puppet.com/docs/puppet/latest/server/release_notes.html).

## Installing Puppet Server

See [Installing Puppet Server from Packages](https://puppet.com/docs/puppet/latest/server/install_from_packages.html)
for complete installation requirements and instructions.

## Ruby and Puppet Server

Puppet Server uses its own JRuby interpreter, which doesn't load gems or other
code from your system Ruby. If you want Puppet Server to load additional gems,
use the Puppet Server-specific `gem` command to install them. See [Puppet
Server and Gems](https://puppet.com/docs/puppet/latest/server/gems.html) for more
information about gems and Puppet Server.

## Configuration

Puppet Server honors almost all settings in `puppet.conf` and should pick them
up automatically. However, we have also introduced some new settings specific
to Puppet Server. See the [Configuration](https://puppet.com/docs/puppet/latest/server/configuration.html)
documentation for details.

For more information on the differences between Puppet Server's support for
`puppet.conf` settings and the Ruby master's, see our documentation of
[differences in `puppet.conf`](https://puppet.com/docs/puppet/latest/server/puppet_conf_setting_diffs.html).

### Certificate authority configuration

Puppet can use its built-in certificate authority (CA) and public key
infrastructure (PKI) tools or use an existing external CA for all of its
secure socket layer (SSL) communications. See certificate authority
[docs](https://puppet.com/docs/puppet/latest/ssl_certificates.html) for details.

### SSL configuration

In network configurations that require external SSL termination, you need to do
a few things differently in Puppet Server. See
[External SSL Termination](https://puppet.com/docs/puppet/latest/server/external_ssl_termination.html)
for details.

## Command-line utilities

Puppet Server provides several command-line utilities for development and
debugging purposes. These commands are all aware of
[`puppetserver.conf`](https://puppet.com/docs/puppet/latest/server/configuration.html),
as well as the gems and Ruby code specific to Puppet Server and Puppet, while
keeping them isolated from your system Ruby.

For more information, see [Puppet Server
Subcommands](https://puppet.com/docs/puppet/latest/server/subcommands.html).

## Known issues

As this application is still in development, there are a few [known
issues](https://puppet.com/docs/puppet/latest/server/known_issues.html) 
that you should be aware of.

## Developer documentation

If want to play with our code, these documents should prove useful:

-   [Running Puppet Server from source](https://puppet.com/docs/puppet/latest/server/dev_running_from_source.html)
-   [Debugging](https://puppet.com/docs/puppet/latest/server/dev_debugging.html)
-   [Puppet Server subcommands](https://puppet.com/docs/puppet/latest/server/subcommands.html)

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
