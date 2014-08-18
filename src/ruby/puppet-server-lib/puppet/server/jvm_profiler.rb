require 'java'

# A wrapper class that implements Puppet's profiler API by passing calls
# through to a Java/JVM-based implementation.
class Puppet::Server::JvmProfiler
  def initialize(profiler)
    @profiler = profiler
  end

  def start(description, metric_id)
    @profiler.start(description.to_java, javify_metric_id(metric_id))
  end

  def finish(context, description, metric_id)
    @profiler.finish(context, description.to_java, javify_metric_id(metric_id))
  end

  def shutdown()
    @profiler.shutdown()
  end

  def javify_metric_id(metric_id)
    if metric_id
      metric_id.map { |s| s.to_s }.to_java(:string)
    end
  end
end