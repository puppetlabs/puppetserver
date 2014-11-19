# Puppet Server and Gems

If you have server-side Ruby code in your modules, Puppet Server will run it via
JRuby. Generally speaking, this only affects custom parser functions and report
processors. For the vast majority of cases, this shouldn't pose any problems, as JRuby is highly compatible with vanilla Ruby.

## Installing And Removing Gems

We isolate the Ruby load paths that are accessible to Puppet Server's
JRuby interpreter, so that it doesn't load any gems or other code that
you have installed on your system Ruby. If you want Puppet Server to load additional gems, use the Puppet Server-specific `gem` command to install them: `puppetserver gem install foobar`. 

The `puppetserver gem` command is simply a wrapper around the usual Ruby `gem` command, so all of the usual arguments and flags should work as expected. For example, to show your locally installed gems, run: 

    $ puppetserver gem list

Or, if you're running from source:

    $ lein gem -c /path/to/puppet-server.conf

## Gems with Native (C) Extensions

If, in your custom parser functions or report processors, you're using Ruby gems
that require native (C) extensions, you won't be able to install these gems under
JRuby. In many cases, however, there are drop-in replacements implemented in Java.
For example, the popular [Nokogiri](http://www.nokogiri.org/) gem for processing
XML provides a completely compatible Java implementation that's automatically
installed if you run `gem install` via JRuby or Puppet Server, so you shouldn't need
to change your code at all.

In other cases, there may be a replacement gem available with a slightly different name;
e.g., `jdbc-mysql` instead of `mysql`. The JRuby wiki [C Extension Alternatives](https://github.com/jruby/jruby/wiki/C-Extension-Alternatives)
page discusses this issue further. 

If you're using a gem that won't run on JRuby and you can't find a suitable replacement, please open a ticket on our [Issue Tracker](https://tickets.puppetlabs.com/browse/SERVER); we're definitely interested in helping provide solutions if there are common gems that are causing trouble for users!
