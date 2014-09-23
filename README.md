# Puppet Server

Puppet Server is the next-generation application for managing Puppet agents.
It is the platform that will carry Puppet's server-side components to a more
distributed, service-oriented architecture.  It has been built on top of the
same technologies that have made PuppetDB successful, and will allow us to make
great leaps forward in performance, scalability, advanced metrics collection,
and fine-grained control over the Ruby runtime.

Issue Tracker
-----

Feature requests?  Found a bug?  Want to see what issues are currently in flight?  Please visit our Jira project:

https://tickets.puppetlabs.com/browse/SERVER

Installation
-----

* [Installing Puppet Server from Packages](./documentation/install_from_packages.markdown)

Developer Documentation
-----

* [Running Puppet Server From Source](./documentation/dev_running_from_source.markdown)
* [Debugging and Gems](./documentation/dev_debugging.markdown)

Known Issues
-----

#### Init script behavior on EL 5 and 6

On EL5 and EL6, if the service is started by executing the init script directly
while inside a directory that the puppetserver user doesn't have access to, the
service will start, but log errors to /var/log/puppetserver/puppetserver-daemon.log
that complain about:

> Could not retrieve fact='hostname', resolution='<anonymous>': Failed while executing '/bin/hostname': Cannot run program "/bin/sh" (in directory "/root"): error=13, Permission denied

The current workarounds for this issue are to use `service puppetserver start`
instead of `/etc/init.d/puppetserver start` or to invoke the init script from a
directory the puppetserver user will have access to (/usr/share/puppetserver is
a good directory for this).

This is captured in https://tickets.puppetlabs.com/browse/SERVER-3

# Special thanks to

## Cursive Clojure

[Cursive](https://cursiveclojure.com/) is a Clojure IDE based on
[IntelliJ IDEA](http://www.jetbrains.com/idea/download/index.html).  Several of
us at Puppet Labs use it regularly and couldn't live without it.  It's got
some really great editing, refactoring, and debugging features, and the author,
Colin Fleming, has been amazingly helpful and responsive when we have feedback.
If you're a Clojure developer you should definitely check it out!

## JRuby

[JRuby](http://jruby.org/) is an implementation of the Ruby programming language
that runs on the JVM.  It's a fantastic project, and it is the bridge that allows
us to run all of the existing Puppet Ruby code while taking advantage of all of
the advanced features and libraries that are available on the JVM.  We're very
grateful to the developers for building such a great product and for helping us
work through a few bugs that we've discovered along the way.

[leiningen]: https://github.com/technomancy/leiningen

