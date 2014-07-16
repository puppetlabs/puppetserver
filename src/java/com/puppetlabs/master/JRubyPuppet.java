package com.puppetlabs.master;

import java.util.Map;

/**
 *
 * This interface is a bridge between the clojure/Java code and the ruby class
 * `JRubyPuppet`.  (defined in `src/ruby/jvm-puppet-lib/jruby_puppet.rb`.)
 * `com.puppetlabs.master.JRubyPuppet`.  The ruby class uses some
 * JRuby magic that causes it to "implement" the Java interface.
 *
 * So, from the outside (in the clojure/Java code), we can interact with an instance
 * of the ruby class simply as if it were an instance of this interface; thus, consuming
 * code need not be aware of any of the JRuby implementation details.
 *
 */
public interface JRubyPuppet {
    JRubyPuppetResponse handleRequest(Map request);
    Object getSetting(String setting);
    String puppetVersion();
}
