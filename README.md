jvm-puppet
==========


JVM implementation of the Puppet master

# To run the server:

  * Update the git submodules located in `./ruby`
    * `git submodule init`
    * `git submodule update`
  * in the REPL:
    * load the namespace `puppetlabs.jvm-puppet.testutils.repl`
    * `(go)`
  * or, from the command line:

```sh
    lein run --config test-resources/jvm-puppet.conf
```

# Run the agent against jvm-puppet:

```sh
    bin/run-agent.sh
```

# Unit Tests

* Clojure unit tests: `lein test`
* Ruby spec tests: `rake spec`
