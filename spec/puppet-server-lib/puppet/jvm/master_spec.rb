require 'spec_helper'

require 'puppet/server/master'
require 'puppet/server/jvm_profiler'

describe 'Puppet::Server::Master' do
  let :master do
    Puppet::Server::Master.new({}, {})
  end

  context "run mode" do
    subject do
      master.run_mode
    end

    it "is set to 'master'" do
      expect(subject).to eq('master')
    end
  end

  class MasterTestProfiler
    def start(description, metric_id)
      metric_id
    end

    def finish(context, description, metric_id)
    end

    def shutdown()
    end
  end

  context "profiler" do
    it "does not register a profiler if profiler is set to nil" do
      master = Puppet::Server::Master.new({}, {})
      Puppet::Util::Profiler.current.length.should == 0
    end

    it "registers a profiler if the profiler is not nil" do
      master = Puppet::Server::Master.new({}, {"profiler" => MasterTestProfiler.new})
      Puppet::Util::Profiler.current.length.should == 1
    end
  end
end
