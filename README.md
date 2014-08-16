puppet-server
=============

# To run the server:

  * Update the git submodules located in `./ruby`
    * `git submodule init`
    * `git submodule update`
  * in the REPL:
    * load the namespace `puppetlabs.puppet-server.testutils.repl`
    * `(go)`
  * or, from the command line:

```sh
    lein run --config dev-resources/puppet-server.conf
```

# Run the agent against puppet-server:

```sh
    bin/run-agent.sh
```

# Unit Tests

* Clojure unit tests: `lein test`
* Ruby spec tests: `rake spec`
