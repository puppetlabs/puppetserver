require 'spec_helper'

require 'puppet/server/jvm_profiler'

class TestWrappedProfiler
  attr_reader :context, :description, :metric_id, :is_shutdown

  def initialize
    @is_shutdown = false
  end

  def start(description, metric_id)
    @description = description
    @metric_id = metric_id
    "foo"
  end

  def finish(context, description, metric_id)
    @context = context
    @description = description
    @metric_id = metric_id
  end

  def shutdown
    @is_shutdown = true
  end
end

describe Puppet::Server::JvmProfiler do
  let(:wrapped) { TestWrappedProfiler.new }
  let(:profiler) { Puppet::Server::JvmProfiler.new(wrapped) }

  it "converts metric ids to java" do
    converted = profiler.javify_metric_id(["foo", "bar"])
    converted.should be_a java.lang.String[]
    converted[0].should == "foo"
    converted[1].should == "bar"
  end

  it "calls wrapped profiler with java args" do
    context = profiler.start("desc", ["my", "metric"])
    wrapped.description.should be_a java.lang.String
    wrapped.description.should == "desc"
    wrapped.metric_id.should be_a java.lang.String[]
    wrapped.metric_id[0].should == "my"
    wrapped.metric_id[1].should == "metric"
    context.should == "foo"

    profiler.finish(context, "desc", ["my", "metric"])
    wrapped.context.should == "foo"
    wrapped.description.should be_a java.lang.String
    wrapped.description.should == "desc"
    wrapped.metric_id.should be_a java.lang.String[]
    wrapped.metric_id[0].should == "my"
    wrapped.metric_id[1].should == "metric"

    wrapped.is_shutdown.should == false
    profiler.shutdown
    wrapped.is_shutdown.should == true

  end
end