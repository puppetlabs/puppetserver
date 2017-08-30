package com.puppetlabs.puppetserver;

import java.util.Map;
import java.util.List;

/**
 *
 * This interface is a bridge between the clojure/Java code and the ruby class
 * `JRubyPuppet`.  (defined in `src/ruby/puppetserver-lib/jruby_puppet.rb`.)
 * The ruby class uses some JRuby magic that causes it to "implement" the Java
 * interface.
 *
 * So, from the outside (in the clojure/Java code), we can interact with an instance
 * of the ruby class simply as if it were an instance of this interface; thus, consuming
 * code need not be aware of any of the JRuby implementation details.
 *
 */
public interface JRubyPuppet {
    Map getTaskData(String environment, String module, String task);
    List getTasks(String environment);
    Map getClassInfoForEnvironment(String environment);
    List getModuleInfoForEnvironment(String environment);
    Map getModuleInfoForAllEnvironments();
    JRubyPuppetResponse handleRequest(Map request);
    Object getSetting(String setting);
    String puppetVersion();
    void terminate();
}
