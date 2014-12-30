---
layout: default
title: "Puppet Server: Subcommands"
canonical: "/puppetserver/latest/subcommands.html"
---


There are several CLI commands that are provided to help with debugging and
exploring Puppet Server. Most of the commands are the same ones you would use
in a Ruby environment - such as `gem`, `ruby`, and `irb` - except they run
against the JRuby installation & gems that Puppet Server uses instead of your
system Ruby.

The following subcommands are provided:
* [gem](#gem)
* [ruby](#ruby)
* [irb](#irb)
* [foreground](#foreground)

The format for each subcommand is:

```sh
puppetserver <subcommand> [<args>]
```

When running from source, the format is:

```sh
lein <subcommand> -c /path/to/puppet-server.conf [--] [<args>]
```

Note that if one of the `<args>` begins with a `-` (like `--version` or `-e`)
then the intermediate `--` above is required for the argument to be applied to
the subcommand and not leiningen. This is not necessary when running from
packages (i.e. `puppetserver <subcommand>`).

## gem

Manage gems that are isolated from system Ruby and only accessible to Puppet
Server. This is a simple wrapper around the standard Ruby `gem` so all of the
usual arguments and flags should work as expected.

Examples:

```sh
$ puppetserver gem install pry --no-ri --no-rdoc
```

```sh
$ lein gem -c /path/to/puppet-server.conf -- install pry --no-ri --no-rdoc
```

For more information, see [Puppet Server and Gems](./gems.markdown).

## ruby

Interpreter for the JRuby that Puppet Server uses. This is a simple wrapper
around the standard Ruby `ruby` so all of the usual arguments and flags should
work as expected.

Useful when experimenting with gems installed via `puppetserver gem` and the
Puppet and Puppet Server ruby source code.

Examples:

```sh
$ puppetserver ruby -e "require 'puppet'; puts Puppet[:certname]"
```

```sh
$ lein ruby -c /path/to/puppet-server.conf -- -e "require 'puppet'; puts Puppet[:certname]"
```

## irb

Interactive REPL for the JRuby that Puppet Server uses. This is a simple wrapper
around the standard Ruby `irb` so all of the usual arguments and flags should
work as expected.

Like the `ruby` subcommand, this is useful for experimenting in an interactive
environment with any installed gems (via `puppetserver gem`) as well as the
Puppet and Puppet Server ruby source code.

Examples:

```ruby
$ puppetserver irb
irb(main):001:0> require 'puppet'
=> true
irb(main):002:0> puts Puppet[:certname]
centos6-64.localdomain
=> nil
```

## foreground

Start the Puppet Server but don't background it; similar to starting the service
and then tailing the log.

Accepts an optional `--debug` argument to raise the logging level to DEBUG.

Examples:

```java
$ puppetserver foreground --debug
2014-10-25 18:04:22,158 DEBUG [main] [p.t.logging] Debug logging enabled
2014-10-25 18:04:22,160 DEBUG [main] [p.t.bootstrap] Loading bootstrap config from specified path: '/etc/puppetserver/bootstrap.cfg'
2014-10-25 18:04:26,097 INFO  [main] [p.s.j.jruby-puppet-service] Initializing the JRuby service
2014-10-25 18:04:26,101 INFO  [main] [p.t.s.w.jetty9-service] Initializing web server(s).
2014-10-25 18:04:26,149 DEBUG [clojure-agent-send-pool-0] [p.s.j.jruby-puppet-agents] Initializing JRubyPuppet instances with the following settings:
```
