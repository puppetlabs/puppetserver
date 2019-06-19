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
    expect(converted).to be_a java.lang.String[]
    expect(converted[0]).to eq("foo")
    expect(converted[1]).to eq("bar")
  end

  it "calls wrapped profiler with java args" do
    context = profiler.start("desc", ["my", "metric"])
    expect(wrapped.description).to be_a java.lang.String
    expect(wrapped.description).to eq("desc")
    expect(wrapped.metric_id).to be_a java.lang.String[]
    expect(wrapped.metric_id[0]).to eq("my")
    expect(wrapped.metric_id[1]).to eq("metric")
    expect(context).to eq("foo")

    profiler.finish(context, "desc", ["my", "metric"])
    expect(wrapped.context).to eq("foo")
    expect(wrapped.description).to be_a java.lang.String
    expect(wrapped.description).to eq("desc")
    expect(wrapped.metric_id).to be_a java.lang.String[]
    expect(wrapped.metric_id[0]).to eq("my")
    expect(wrapped.metric_id[1]).to eq("metric")

    expect(wrapped.is_shutdown).to eq(false)
    profiler.shutdown
    expect(wrapped.is_shutdown).to eq(true)

  end
end
