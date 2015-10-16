---
layout: default
title: "Puppet Server: Running From Source"
canonical: "/puppetserver/latest/dev_running_from_source.html"
---

So you'd like to run Puppet Server from source?
-----

Before we get started, let's highlight a few gotchas that might save you some time.
The things that you are most likely to run into trouble with are pretty much the
same things that were tricky about running the Webrick server from source:

* Knowing where your confdir/vardir/codedir are
* Knowing what your certname is
* Making sure that your certname is resolvable

In Puppet Server, we always specify /vardir/codedir explicitly in the Puppet Server
config.  This way, they are never dynamically determined based on your user or
anything magical like that.  So, if you need to know where they are, look at your
config file(s).

Your certname comes from `facter fqdn` or puppet.conf, just like it does in Webrick.
If `facter fqdn` returns something that is not resolvable, you're gonna have a bad
time.  Either set your certname explicitly in <confdir>/puppet.conf, or put
`facter fqdn` into your /etc/hosts.

The following steps will help you get Puppet Server up and running from source.

Step 0: Quick Start for Developers
-----

    # clone git repository and initialize submodules
    $ git clone --recursive git://github.com/puppetlabs/puppet-server
    $ cd puppet-server

    # Remove any old config if you want to make sure you're using the latest
    # defaults
    $ rm -rf ~/.puppet-server
    # Copy the sample repl utilities namespace
    $ cp dev/user.clj.sample dev/user.clj

    # Launch the clojure REPL
    $ lein repl
    # Run Puppet Server
    user=> (go)
    user=> (help)

You should now have a running server.  Your confdir is set to `./target/master-conf`;
see ~/.puppet-server/puppet-server.conf to examine this and other default paths.

In another shell, you can run the agent:

    # Go to the directory where you checked out puppet-server
    $ cd puppet-server
    # Set ruby and bin paths
    $ export RUBYLIB=./ruby/puppet/lib:./ruby/facter/lib
    $ export PATH=./ruby/puppet/bin:./ruby/facter/bin:$PATH
    # Create the modules directory for the production environment, to avoid the
    #  annoying module error during pluginsync
    $ mkdir -p ./target/master-code-dir/environments/production/modules
    # Run the agent
    $ puppet agent --no-daemonize --debug --trace --verbose \
         --confdir ./target/master-conf-dir \
         --vardir=./target/master-var-dir \
         --server `facter fqdn` --onetime

**NOTE** that the default config is not ideal for long-lived dev environments,
because it sets codedir, confdir, and many other things to live inside of
`./target`.  This directory *WILL BE COMPLETELY REMOVED* any time you run
a `lein clean`, so if you've been setting up any modules / manifests in there,
you'll lose your work.  It's much better to create a longer-lived home for
these files.  To do that, you just need to edit the settings in your `puppet-server.conf`
file.  More details below.

Also, I generally prefer to use separate confdir and vardir settings for my master
and agent, because otherwise it's super confusing to try to guess which one of them
touches which files otherwise.  To use separate conf/vardirs between master and
agent on Puppet Server, you just need to:

* Choose one set of directories for the master via `puppet-server.conf`
* Choose a different set of directories for the agent via CLI args or
    `puppet.conf`'s `[agent]` section
* Use a different certname for the master than for the agent.  (I usually put
    `certname=localhost` into my master's `puppet.conf` file, use a different
    confdir for the agent, and do `--server localhost` on the CLI for my
    agent runs.  This also has the pleasant side effect of not needing to jack
    with `/etc/hosts`/`facter fqdn` for the master.

More detailed instructions follow.


Step 1: Install Prerequisites
-----

Use your system's package tools to ensure that the following prerequisites are installed:

* JDK 1.7 or higher
* [Leiningen](http://leiningen.org/)
* Git (for checking out the source code)


Step 2: Clone Git Repo and Set Up Working Tree
-----

    $ git clone git://github.com/puppetlabs/puppet-server
    $ cd puppet-server
    # initialize git submodules (which are located in ./ruby, and contain the
    #   puppet/facter ruby source code)
    $ git submodule init
    $ git submodule update

Step 3: Set up Config Files
-----

Choose a directory outside of the git working copy, e.g. `~/.puppet-server`, where you'd
like to maintain local config files for development.  Then, copy the file
`dev/puppet-server.conf.sample` to that directory, and name it `puppet-server.conf`.
Edit it to suit your needs.  A few notes:

* Relative paths in the config file refer to the root directory of the puppet-server
  git working copy.
* Puppet Server reads much of its configuration data from `puppet.conf` and Puppet's
  usual `confdir`.  The setting `master-conf-dir` may be used to modify the location
  of the Puppet `confdir`.  Otherwise, it will use the same default locations as
  Puppet normally uses (`~/.puppet` for non-root users, `/etc/puppet` for root).
* Similar to `confdir`, you will likely want to specify the other top-level
  Puppet directories `codedir`, `vardir`, `rundir`, and `logdir`.

You probably don't need to make any changes to the sample config for your first run,
but the settings that I edit the most frequently are:

 * `jruby-puppet.master-conf-dir`: the puppet master confdir (where `puppet.conf`,
   `modules`, `manifests`, etc. should be located).
 * `jruby-puppet.master-code-dir`: the puppet master codedir
 * `jruby-puppet.master-var-dir`: the puppet master vardir
 * `jruby-puppet.master-run-dir`: the puppet master rundir
 * `jruby-puppet.master-log-dir`: the puppet master logdir
 * `jruby-puppet.max-active-instances`: the number of JRuby instances to put into the
   pool.  This can usually be set to 1 for dev purposes, unless you're working on
   something that involves concurrency.

If you prefer, you may break the individual configuration sections from `puppet-server.conf`
into multiple `.conf` files, and place them in a `conf.d`-style directory.

Step 4a: Run the server from the clojure REPL
-----

The preferred way of running the server for development purposes is to run it from
inside the clojure REPL.  The git repo includes some files in the `/dev` directory
that are intended to make this process easier.

When running a clojure REPL via the `lein repl` command-line command, lein will look
for a namespace called `user.clj` on the classpath, and if one is found, it will
load that namespace by default.  We provide a sample version of this namespace that
is tailored for use with Puppet Server.  To use it, simply do:

    $ cp dev/user.clj.sample dev/user.clj

The git repo contains a `.gitignore` for `dev/user.clj`, so each user can customize
their user namespace to their liking.

Once you've made a copy of the `user.clj` file you'll want to edit it.  It contains
some comments explaining the contents of the file.  The main change you'll want to
make is to edit the `puppet-server-conf` function to your liking; you'll probably just
want to change it so that it points to the path of the `puppet-server.conf` file that
you created in step 3.

Running the server inside of the clojure REPL allows you to make changes to the
source code and reload the server without having to restart the entire JVM.  It
can be much faster than running from the command line, when you are doing iterative
development.  We are also starting to build up a library of utility functions that
can be used to inspect and modify the state of the running server; see `dev/user_repl.clj`
for more info.

(NOTE: many of the developers of this project are using a more full-featured IDE called
[Cursive Clojure](https://cursiveclojure.com/), built on the IntelliJ IDEA platform, for
our daily development.  It contains an integrated REPL that can be used in place of
the `lein repl` command-line command, and works great with all of the functions described
in this document.)

To start the server from the REPL, run the following:

    $ lein repl
    nREPL server started on port 47631 on host 127.0.0.1
    user=> (go)
    user=> (help)


Then, if you make changes to the source code, all you need to do in order to
restart the server with the latest changes is:

    user-repl=> (reset)

Restarting the server this way should be significantly faster than restarting
the entire JVM process.

You can also run the utility functions to inspect the state of the server, e.g.:

    user-repl=> (print-puppet-environment-states)

Have a look at `user_repl.clj` if you're interested in seeing what other utility
functions are available.

Step 4b: Run the server from the command line
-----

If you prefer not to run the server interactively in the REPL, you can launch it
as a normal process.  To start the Puppet Server when running from source, simply
run the following:

    $ lein run -c /path/to/puppet-server.conf

Other useful commands for developers:
-----

* `lein test` to run the clojure test suite
* `rake spec` to run the jruby test suite

Installing Ruby Gems for Development
-----

Please see the [Gems](./gems.markdown) document for detailed information on
installing Ruby Gems for use in development and testing contexts.

Debugging
------

For more information about debugging both Clojure and JRuby code, please see
[Puppet Server: Debugging](./dev_debugging.markdown) documentation.
