(ns puppetlabs.services.protocols.puppet-profiler)

(defprotocol PuppetProfilerService
  "This protocol describes a service that provides a profiler that
  can be used to profile the internals of the Puppet ruby code."
  (get-profiler [this]
                "Returns an instance of `com.puppetlabs.master.PuppetProfiler` that
                can be used by the ruby Puppet profiling system."))