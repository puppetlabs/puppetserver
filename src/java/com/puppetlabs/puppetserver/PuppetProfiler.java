package com.puppetlabs.puppetserver;

/**
 * This interface specifies Java method signatures that map to the API of the
 * Ruby Puppet Profiler.  JVM implementations of this interface can be wired in
 * to the Puppet Server and used to provide profiling support.
 */
public interface PuppetProfiler {
    Object start(String message, String[] metric_id);
    void finish(Object context, String message, String[] metric_id);
    void shutdown();
}
