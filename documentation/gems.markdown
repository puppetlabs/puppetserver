Puppet Server and Gems
========================

If you have server-side Ruby code in your modules, Puppet Server will run it via
JRuby.  Generally speaking this only affects custom parser functions and report
processors, and for the vast majority of cases, this should not pose any problems
whatsoever as JRuby does a great job in terms of compatibility with vanilla Ruby.

Installing And Removing Gems
-----

We do take some care to isolate the Ruby load paths that are accessible to the
JRuby interpreter, so that it doesn't attempt to load any gems or other code that
you have installed on your system Ruby. To achieve this, we ship a
"Puppet Server"-specific `gem` command with the application, and you can use this
command to install or remove gems for Puppet Server.  To use this tool, simply run, e.g.:

    $ puppetserver gem list

Or, if you're running from source:

    $ lein gem -c /path/to/puppet-server.conf

This command is simply a wrapper around the usual Ruby `gem` command, so all of
the usual arguments and flags should work as expected.

Gems with Native (C) Extensions
-----

If, in your custom parser functions or report processors, you are using ruby gems
that require native (C) extensions, these gems won't be able to be installed under
JRuby.  In many cases, there are drop-in replacements that are implemented in Java;
for example, the popular [Nokogiri](http://www.nokogiri.org/) gem for processing
XML provides a completely compatible Java implementation that will automatically be
installed if you run `gem install` via JRuby (or Puppet Server), so you shouldn't need
to change your code at all.

In other cases, there may be a replacement gem available with a slightly different name;
e.g. `jdbc-mysql` instead of `mysql`.  [Here](https://github.com/jruby/jruby/wiki/C-Extension-Alternatives)
is a wiki page from the JRuby web site that discusses this issue.  If you are
using a gem that won't run on JRuby and you can't find a suitable replacement,
please open a ticket on our [Issue Tracker](https://tickets.puppetlabs.com/browse/SERVER);
we're definitely interested in helping provide solutions if there are common gems
that are causing trouble for users!
