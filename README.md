# Puppet Server

Puppet Server is the next-generation application for managing Puppet agents.
This platform will carry Puppet's server-side components to a more
distributed, service-oriented architecture. We've built Puppet Server on top of the
same technologies that have made PuppetDB successful, allowing us to
greatly improve performance, scalability, advanced metrics collection,
and fine-grained control over the Ruby runtime.

While Puppet Server is meant to replace an existing
Apache/Passenger Puppet master stack, there are a handful of differences
due to changes in the underlying architecture. Please see [Puppet Server vs. Apache/Passenger Puppet Master](./documentation/puppetserver_vs_passenger.markdown) for details.

## Installing Puppet Server

Puppet Server depends on Puppet 3.7.3 or later, so if you install it on a system running an older version of Puppet, installation will also upgrade Puppet itself. Please see [Installing Puppet Server from Packages](./documentation/install_from_packages.markdown) for complete installation  requirements and instructions.

## Ruby and Puppet Server

Puppet Server is compatible with Ruby 1.9. If you are installing
Puppet Server on an existing system with Ruby 1.8, the behavior of some extensions, such as custom functions and custom resource types and providers, might change slightly. Generally speaking, this shouldn't affect core Puppet Ruby code, which is tested against both versions of Ruby.

Puppet Server uses its own JRuby interpreter, which doesn't load gems or other code from your system Ruby. If you want Puppet Server to load any additional gems, use the Puppet Server-specific `gem` command to install them. See [Puppet Server and Gems](./documentation/gems.markdown) for more information about gems and Puppet Server.

## Configuration

Puppet Server honors almost all settings in `puppet.conf` and should pick them
up automatically. However, we have also introduced some new Puppet
Server-specific settings---please see the [Configuration]
(./documentation/configuration.markdown) page for details. For more information
on the specific differences in Puppet Server's support for `puppet.conf`
settings as compared to the Ruby master, see the [puppet.conf differences]
(./documentation/puppet_conf_setting_diffs.markdown) page.

### Certificate Authority Configuration

Much of the existing documentation on [External CA Support for the Ruby Puppet Master](https://docs.puppetlabs.com/puppet/latest/reference/config_ssl_external_ca.html)
still applies to using an external Certificate Authority in conjunction with Puppet Server. There are some  differences to bear in mind, however; see the [External CA Configuration](./documentation/external_ca_configuration.markdown) page for details.

### SSL Configuration

In network configurations that require external SSL termination, you need to do a few things differently in Puppet Server. Please see the [External SSL Termination](./documentation/external_ssl_termination.markdown) page for details.

## CLI Commands

Puppet Server provides several command-line utilities useful for development
and debugging purposes. These commands are all aware of `puppetserver.conf`
and the gems and Ruby code specific to Puppet Server and Puppet, while keeping
them isolated from your system Ruby.

For more information, see [Puppet Server Subcommands](./documentation/subcommands.markdown).

## Known Issues

As this application is still in development, there are a few [known issues](./documentation/known_issues.markdown) that you should be aware of.

## Developer Documentation

If you are a developer who wants to play with our code, these documents should prove useful:
* [Running Puppet Server From Source](./documentation/dev_running_from_source.markdown)
* [Debugging](./documentation/dev_debugging.markdown)
* [Puppet Server Subcommands](./documentation/subcommands.markdown)

## Branching Strategy

The Branching Strategy for Puppet Server is documented on the wiki [here](https://github.com/puppetlabs/puppetserver/wiki/Branching-Strategy).

## Issue Tracker

Feature requests?  Found a bug?  Want to see what issues are currently in flight?  Please visit our [Jira project](https://tickets.puppetlabs.com/browse/SERVER).

## License

Copyright © 2013 -- 2014 Puppet Labs

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)

### Special thanks to

#### Cursive Clojure

[Cursive](https://cursiveclojure.com/) is a Clojure IDE based on
[IntelliJ IDEA](http://www.jetbrains.com/idea/download/index.html).  Several of
us at Puppet Labs use it regularly and couldn't live without it.  It's got
some really great editing, refactoring, and debugging features, and the author,
Colin Fleming, has been amazingly helpful and responsive when we have feedback.
If you're a Clojure developer you should definitely check it out!

#### JRuby

[JRuby](http://jruby.org/) is an implementation of the Ruby programming language
that runs on the JVM.  It's a fantastic project, and it is the bridge that allows
us to run all of the existing Puppet Ruby code while taking advantage of all of
the advanced features and libraries that are available on the JVM.  We're very
grateful to the developers for building such a great product and for helping us
work through a few bugs that we've discovered along the way.

[leiningen]: https://github.com/technomancy/leiningen
