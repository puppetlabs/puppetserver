---
layout: default
title: "Puppet Server: Debugging"
canonical: "/puppetserver/latest/dev_debugging.html"
---


Because Puppet Server executes both Clojure and Ruby code, approaches to debugging
differ depending on which part of the application you're interested in.

Debugging Clojure Code
-----

If you are interested in debugging the web service layer or other parts of the
app that are written in Clojure, there are lots of options available.  The Clojure
REPL is often the most useful tool, as it makes it very easy to interact with
individual functions and namespaces.

If you are looking for more traditional debugging capabilities, such as defining
breakpoints and stepping through the lines of your source code, there are many
options.  Just about any Java debugging tool will work to some degree, but
Clojure-specific tools such as [CDT](http://georgejahad.com/clojure/cdt.html) and
[debug-repl](http://github.com/georgejahad/debug-repl) will have better integration
with your Clojure source files.

For a more full-featured IDE, [Cursive](https://cursiveclojure.com/) is a great
option.  It's built on [IntelliJ IDEA](http://www.jetbrains.com/idea/), and
provides a debug REPL that supports all of the same debugging features that are
available in Java; breakpoints, evaluating expressions in the local scope when
stopped at a breakpoint, visual navigation of the call stack across threads, etc.

Debugging Ruby Code
-----

Debugging the Ruby code running in Puppet Server can be a bit trickier, because
Java and Clojure debugging tools will only take you into the JRuby interpreter
source code, not into the Ruby code that it is processing.  So, if you wish to
debug the Ruby code directly, you'll need to install gems and take advantage of
their capabilities (not unlike how you would debug Ruby code in the MRI interpreter).

For more info on installing gems for Puppet Server, see [Puppet Server and Gems](./gems.markdown).

## `ruby-debug`

There are many gems available that provide various ways of debugging Ruby code
depending on what version of Ruby and which Ruby interpreter you're running.
One of the most common gems is `ruby-debug`, and there is a JRuby-compatible
version available.  To install it for use in Puppet Server, run:

    $ puppetserver gem install ruby-debug

Or, if you're running puppetserver from source:

    $ lein gem -c /path/to/puppet-server.conf install ruby-debug

After installing the gem, you can trigger the debugger by adding a line like this
to any of the Ruby code that is run in Puppet Server (including the Puppet Ruby
code):

    require 'ruby-debug'; debugger

## `pry`

Pry is another popular gem for introspecting Ruby code.  It is compatible with
JRuby, so you can install it via:

    $ puppetserver gem install pry

Or, if you're running puppetserver from source:

    $ lein gem -c /path/to/puppet-server.conf install pry

After installing, you can add a line like this to the Ruby code:

    require 'pry'; binding.pry

This will give you an advanced interactive REPL at the line of code where you've
called pry.

There are many other gems that are useful for debugging, and a large percentage
of them are compatible with JRuby.  If you have a favorite that is not mentioned
here please let us know, and we will consider adding it to this documentation!

## Limitations

We are aware that some favorite gems/tools/features for ruby debugging don't currently
work with JRuby/Puppet Server.  (For example, some things like color syntax highlighting
in Pry.)  It's important to us to make sure that the Ruby developer experience is not
degraded for developers working via Puppet Server rather than webrick, so, if you run
into issues like this, please file an issue on  our [Bug Tracker](https://tickets.puppetlabs.com/browse/SERVER),
and we will see if it's possible to add support for things that we're missing.  In many
cases it might be a matter of simply submitting a patch to JRuby, or submitting a
JRuby-compatibility patch for an existing gem, and we're interested in trying to help
with those sorts of things whenever possible.

Tracing Code Events
===================

Puppet Server can utilize JRuby's standard facilities for tracing events during
code execution.  For more information on these techniques, see the
[Tracing Code Events](./dev_trace_func.markdown) page.