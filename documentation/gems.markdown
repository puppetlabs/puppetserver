---
layout: default
title: "Puppet Server: Using Ruby Gems"
canonical: "/puppetserver/latest/gems.html"
---


If you have server-side Ruby code in your modules, Puppet Server will run it
via JRuby. Generally speaking, this only affects custom parser functions,
types, and report processors. For the vast majority of cases this shouldn't
pose any problems because JRuby is highly compatible with vanilla Ruby.

## Installing And Removing Gems

We isolate the Ruby load paths that are accessible to Puppet Server's
JRuby interpreter, so that it doesn't load any gems or other code that
you have installed on your system Ruby. If you want Puppet Server to load
additional gems, use the Puppet Server-specific `gem` command to install them.
For example, to install the foobar gem, use:

`puppetserver gem install foobar`

The `puppetserver gem` command is simply a wrapper around the usual Ruby `gem`
command, so all of the usual arguments and flags should work as expected.
For example, to show your locally installed gems, run:

    $ puppetserver gem list

Or, if you're running from source:

    $ lein gem -c ~/.puppet-server/puppet-server.conf list

## Installing Gems for use with development:

When running from source, JRuby uses a `GEM_HOME` of `./target/jruby-gems`
relative to the current working directory of the process.  `lein gem` should be
used to install gems into this location using jruby.

NOTE: `./target/jruby-gems` is not used when running the JRuby spec tests, gems
are instead automatically installed into and loaded from `./vendor/test_gems`.
If you need to install a gem for use both during development and testing make
sure the gem is available in both directories.

As an example, the following command installs `pry` locally in the project.
Note the use of `--` to pass the following command line arguments to the gem
script.

    $ lein gem --config ~/.puppet-server/puppet-server.conf -- install pry \
      --no-ri --no-rdoc
    Fetching: coderay-1.1.0.gem (100%)
    Successfully installed coderay-1.1.0
    Fetching: slop-3.6.0.gem (100%)
    Successfully installed slop-3.6.0
    Fetching: method_source-0.8.2.gem (100%)
    Successfully installed method_source-0.8.2
    Fetching: spoon-0.0.4.gem (100%)
    Successfully installed spoon-0.0.4
    Fetching: pry-0.10.1-java.gem (100%)
    Successfully installed pry-0.10.1-java
    5 gems installed

With the gem installed into the project tree `pry` can be invoked from inside
Ruby code.  For more detailed information on `pry` see
[Puppet Server: Debugging](./dev_debugging.markdown#pry).

## Gems with Native (C) Extensions

If, in your custom parser functions or report processors, you're using Ruby
gems that require native (C) extensions, you won't be able to install these gems
under JRuby. In many cases, however, there are drop-in replacements implemented
in Java. For example, the popular [Nokogiri](http://www.nokogiri.org/) gem for
processing XML provides a completely compatible Java implementation that's
automatically installed if you run `gem install` via JRuby or Puppet Server,
so you shouldn't need to change your code at all.

In other cases, there may be a replacement gem available with a slightly
different name; e.g., `jdbc-mysql` instead of `mysql`. The JRuby wiki
[C Extension Alternatives](https://github.com/jruby/jruby/wiki/C-Extension-Alternatives)
page discusses this issue further.

If you're using a gem that won't run on JRuby and you can't find a suitable
replacement, please open a ticket on our
[Issue Tracker](https://tickets.puppetlabs.com/browse/SERVER); we're definitely
interested in helping provide solutions if there are common gems that are
causing trouble for users!
