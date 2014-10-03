Puppet Server vs. Apache/Passenger Puppet Master
========================

Puppet Server is intended to function as a drop-in replacement for the existing
Apache/Passenger Puppet Master stack.  However, there are a handful of differences
due to changes in the underlying architecture.

This page details things that are intentionally different between the two
applications; you may also be interested in the [Known Issues](./known_issues.markdown)
page, where we've listed a handful of issues that we expect to fix in future releases.


Service Name
-----

Since the Apache/Passenger master runs under, um, Apache, the name of the service
that you use to start and stop the master is `httpd` (or `apache2`, depending
on your platform).  With Puppet Server, the service name is `puppetserver`.  So
to start and stop the service, you'll use, e.g., `service puppetserver restart`.

Config Files
-----

Puppet Server honors *almost* all settings in `puppet.conf`, and should pick them
up automatically.  It does introduce new settings for some "Puppet Server"-specific
things, though.  For example, the webserver interface and port.  For a complete
list of the new settings, see the [Configuration](./configuration.markdown) docs
page.

Gems
-----

If you have server-side Ruby code in your modules, Puppet Server will run it via
JRuby.  Generally speaking this only affects custom parser functions and report
processors, and for the vast majority of cases, this should not pose any problems
whatsoever as JRuby does a great job in terms of compatibility with vanilla Ruby.

We do take some care to isolate the Ruby load paths that are accessible to the
JRuby interpreter, so that it doesn't attempt to load any gems or other code that
you have installed on your system Ruby. To achieve this, we ship a
"Puppet Server"-specific `gem` command with the application, and you can use this
command to install or remove gems for Puppet Server.

For more details on how Puppet Server interacts with gems, see the [Puppet Server and Gems](./gems.markdown)
page.

Startup Time
-----

Puppet Server runs on the JVM, and as a result, it takes a bit longer for the
server to start up and be ready to accept HTTP connections as compared to the
Apache/Passenger stack.  We see significant performance improvements after start up,
but the initial start up is definitely a bit slower.