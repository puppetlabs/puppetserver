---
layout: default
title: "Puppet Server: Running From Source"
canonical: "/puppetserver/latest/dev_running_from_source.html"
---

So you'd like to run Puppet Server from source?
-----

The following steps will help you get Puppet Server up and running from source.

Step 0: Quick Start for Developers
-----

    # clone git repository and initialize submodules
    $ git clone --recursive git://github.com/puppetlabs/puppetserver
    $ cd puppetserver

    # Remove any old config if you want to make sure you're using the latest
    # defaults
    $ rm -rf ~/.puppetserver

    # Run the `dev-setup` script to initialize all required configuration
    $ ./dev-setup

    # Launch the clojure REPL
    $ lein repl
    # Run Puppet Server
    dev-tools=> (go)
    dev-tools=> (help)

You should now have a running server.  All relevant paths (`$confdir`, `$codedir`,
etc.) are configured by default to point to directories underneath `~/.puppetlabs`.
These should all align with the default values that `puppet` uses (for non-root
users).

You can find the specific paths in the `dev/puppetserver.conf` file.

In another shell, you can run the agent:

    # Go to the directory where you checked out puppetserver
    $ cd puppetserver
    # Set ruby and bin paths
    $ export RUBYLIB=./ruby/puppet/lib:./ruby/facter/lib
    $ export PATH=./ruby/puppet/bin:./ruby/facter/bin:$PATH
    # Run the agent
    $ puppet agent -t

More detailed instructions follow.

Step 1: Install Prerequisites
-----

Use your system's package tools to ensure that the following prerequisites are installed:

* JDK 1.7 or higher
* [Leiningen](http://leiningen.org/)
* Git (for checking out the source code)


Step 2: Clone Git Repo and Set Up Working Tree
-----

    $ git clone --recursive git://github.com/puppetlabs/puppetserver
    $ cd puppetserver

Step 3: Set up Config Files
-----

The easiest way to do this is to just run:

    $ ./dev-setup

This will set up all of the necessary configuration files and directories inside
of your `~/.puppetlabs` directory.  If you are interested in seeing what all of the
default file paths are, you can find them in `./dev/puppetserver.conf`.

The default paths should all align with the default values that are used by `puppet`
(for non-root users).

If you'd like to customize your environment, here are a few things you can do:

* Before running `./dev-setup`, set an environment variable called `MASTERHOST`.
  If this variable is found during `dev-setup`, it will configure your `puppet.conf`
  file to use this value for your certname (both for Puppet Server and for `puppet`)
  and for the `server` configuration (so that your agent runs will automatically
  use this hostname as their puppet master).
* Create a file called `dev/user.clj`.  This file will be automatically loaded
  when you run Puppet Server from the REPL.  In it, you can define a function
  called `get-config`, and use it to override the default values of various settings
  from `dev/puppetserver.conf`.  For an example of what this file should look like,
  see `./dev/user.clj.sample`.

You don't need to create a `user.clj` in most cases; the settings that I change
the most frequently that would warrant the creation of this file, though, are:

 * `jruby-puppet.max-active-instances`: the number of JRuby instances to put into the
    pool.  This can usually be set to 1 for dev purposes, unless you're working on
    something that involves concurrency.
 * `jruby-puppet.splay-instance-flush`: Do not attempt to splay JRuby flushing, set
    when testing if using multiple JRuby instances and you need to control when they
    are flushed from the pool
 * `jruby-puppet.master-conf-dir`: the puppet master confdir (where `puppet.conf`,
   `modules`, `manifests`, etc. should be located).
 * `jruby-puppet.master-code-dir`: the puppet master codedir
 * `jruby-puppet.master-var-dir`: the puppet master vardir
 * `jruby-puppet.master-run-dir`: the puppet master rundir
 * `jruby-puppet.master-log-dir`: the puppet master logdir


Step 4a: Run the server from the clojure REPL
-----

The preferred way of running the server for development purposes is to run it from
inside the clojure REPL.  The git repo includes some files in the `/dev` directory
that are intended to make this process easier.

When running a clojure REPL via the `lein repl` command-line command, lein will load
the `dev/dev-tools.clj` namespace by default.

Running the server inside of the clojure REPL allows you to make changes to the
source code and reload the server without having to restart the entire JVM.  It
can be much faster than running from the command line, when you are doing iterative
development.  We are also starting to build up a library of utility functions that
can be used to inspect and modify the state of the running server; see `dev/dev-tools.clj`
for more info.

(NOTE: many of the developers of this project are using a more full-featured IDE called
[Cursive Clojure](https://cursiveclojure.com/), built on the IntelliJ IDEA platform, for
our daily development.  It contains an integrated REPL that can be used in place of
the `lein repl` command-line command, and works great with all of the functions described
in this document.)

To start the server from the REPL, run the following:

    $ lein repl
    nREPL server started on port 47631 on host 127.0.0.1
    dev-tools=> (go)
    dev-tools=> (help)

Then, if you make changes to the source code, all you need to do in order to
restart the server with the latest changes is:

    dev-tools=> (reset)

Restarting the server this way should be significantly faster than restarting
the entire JVM process.

You can also run the utility functions to inspect the state of the server, e.g.:

    dev-tools=> (print-puppet-environment-states)

Have a look at `dev-tools.clj` if you're interested in seeing what other utility
functions are available.

Step 4b: Run the server from the command line
-----

If you prefer not to run the server interactively in the REPL, you can launch it
as a normal process.  To start the Puppet Server when running from source, simply
run the following:

    $ lein run -c /path/to/puppetserver.conf

Running the Agent
-----

Use a command like the one below to run an agent against your running puppetserver:

        puppet agent --confdir ~/.puppetlabs/etc/puppet \
                     --debug -t

Note that a system installed Puppet Agent is ok for use with
source-based PuppetDB and Puppet Server. The `--confdir` above
specifies the same confdir that Puppet Server is using. Since the
Puppet Agent and Puppet Server instances are both using the same
confdir, they're both using the same certificates as well. This
alleviates the need to sign certificates as a separate step.

Running tests
-----

* `lein test` to run the clojure test suite
* `rake spec` to run the jruby test suite

The Clojure test suite can consume a lot of transient memory.  Using a larger
JVM heap size when running tests can significantly improve test run time.  The
default heap size is somewhat conservative: 1 GB for the minimum heap (much
lower than that as a maximum can lead to Java OutOfMemory errors during the
test run) and 2 GB for the maximum heap.  While the heap size can be configured
via the `-Xms` and `-Xmx` arguments for the `:jvm-opts` `defproject` key within
the `project.clj` file, it can also be customized for an individual user
environment via either of the following methods:

1) An environment variable named `PUPPETSERVER_HEAP_SIZE`.  For example, to
  use a heap size of 6 GiB for a `lein test` run, you could run the following:

    $ PUPPETSERVER_HEAP_SIZE=6G lein test

2) A lein `profiles.clj` setting in the `:user` profile under the
  `:puppetserver-heap-size` key.  For example, to use a heap size of 6 GiB, you
  could add the following key to your `~/.lein/profiles.clj` file:

```clj
{:user {:puppetserver-heap-size "6G"
        ...}}

```

With the `:puppetserver-heap-size` key defined in the `profiles.clj` file, any
subsequent `lein test` run would utilize the associated value for the key.  If
both the environment variable and the `profiles.clj` key are defined, the
value from the environment variable takes precedence.  When either of these
settings is defined, the value is used as both the minimum and maximum JVM heap
size.

From anecdotal testing from the puppetserver master branch as of 10/26/2016,
it appeared that at least a heap size of 5 GB would provide the best performance
benefit for full runs of the Clojure unit test suite.  This value may change
over time depending upon how the tests evolve.

Installing Ruby Gems for Development
-----

The gems that are vendored with the puppetserver OS packages will be automatically
installed into your dev environment by the `./dev-setup` script.  If you wish
to install additional gems, please see the [Gems](./gems.markdown) document for
detailed information.

Debugging
------

For more information about debugging both Clojure and JRuby code, please see
[Puppet Server: Debugging](./dev_debugging.markdown) documentation.

Running PuppetDB
-----

To run a source PuppetDB with Puppet Server, Puppet Server needs
standard PuppetDB configuration and how to find the PuppetDB
terminus. First copy the `dev/puppetserver.conf` file to another
directory. In your copy of the config, append a new entry to the
`ruby-load-path` list: `<PDB source path>/puppet/lib`. This tells
PuppetServer to load the PuppetDB terminus from the specified
directory.

From here, the instructions are similar to installing PuppetDB
manually via packages. The PuppetServer instance needs configuration
for connecting to PuppetDB. Instructions on this configuration are
below, but the official docs for this can be found
[here](https://docs.puppet.com/puppetdb/4.3/connect_puppet_master.html).

Update `~/.puppetlabs/etc/puppet/puppet.conf` to include:

    [master]
    storeconfigs = true
    storeconfigs_backend = puppetdb
    reports = store,puppetdb

Create a new puppetdb config file
`~/.puppetlabs/etc/puppet/puppetdb.conf` that contains

    [main]
    server_urls = https://<MASTERHOST>:8081

Then create a new routes file at
`~/.puppetlabs/etc/puppet/routes.yaml` that contains

    ---
    master:
      facts:
        terminus: puppetdb
        cache: yaml

Assuming you have a PuppetDB instance up and running, start your Puppet
Server instance with the new puppetserver.conf file that you changed:

    lein run -c ~/<YOUR CONFIG DIR>/puppetserver.conf

Depending on your PuppetDB configuration, you might need to change
some SSL config. PuppetDB requires that the same CA that signs it's
certificate, also has signed Puppet Server's certificate. The easiest
way to do this is to point PuppetDB at the same configuration
directory that Puppet Server and Puppet Agent are pointing
to. Typically this setting is specified in the `jetty.ini` file in the
PuppetDB conf.d directory. The update would look like:

    [jetty]

    #...
    ssl-cert = <home dir>/.puppetlabs/etc/puppet/ssl/certs/<MASTERHOST>.pem
    ssl-key = <home dir>/.puppetlabs/etc/puppet/ssl/private_keys/<MASTERHOST>.pem
    ssl-ca-cert = <home dir>/.puppetlabs/etc/puppet/ssl/certs/ca.pem

Once the SSL config is in place, start (or restart) PuppetDB:

    lein run services -c <path to PDB config>/conf.d

Then run the Puppet Agent and you should see activity in PuppetDB and
Puppet Server.
