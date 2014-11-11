Puppet Server: Running From Source
======================================

The following steps will help you get Puppet Server up and running from source.

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
* Puppet Server reads most of its configuration data from `puppet.conf` and Puppet's
  usual `confdir`.  The setting `master-conf-dir` may be used to modify the location
  of the Puppet `confdir`.  Otherwise, it will use the same default locations as
  Puppet normally uses (`~/.puppet` for non-root users, `/etc/puppet` for root).

Alternately you may break the individual configuration settings from `puppet-server.conf`
into multiple `.conf` files, and place them in a `conf.d`-style directory.

Step 4a: Run the server from the command line
-----

This will let you develop on Puppet Server and see your changes by simply editing
the code and restarting the server. It will not create an init script or default
configuration directory. To start the Puppet Server when running from source, you
will need to run the following:

    $ lein run -c /path/to/puppet-server.conf

Step 4b: Run the server from the clojure REPL
-----

Running the server inside of the clojure REPL allows you to make changes to the
source code and reload the server without having to restart the entire JVM.  It
can be much faster than running from the command line, when you are doing iterative
development.  To start the server from the REPL, run the following:

    $ lein repl
    nREPL server started on port 47631 on host 127.0.0.1
    puppetlabs.trapperkeeper.main=> (load-file "./dev/user_repl.clj")
    #'user-repl/reset
    puppetlabs.trapperkeeper.main=> (ns user-repl)
    nil
    user-repl=> (go)

Then, if you make changes to the source code, all you need to do in order to
restart the server with the latest changes is:

    user-repl=> (reset)

Restarting the server this way should be significantly faster than restarting
the entire JVM process.

Other useful commands for developers:
-----

* `lein test` to run the clojure test suite
* `rake spec` to run the jruby test suite

