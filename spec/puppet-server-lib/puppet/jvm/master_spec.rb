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
      expect(Puppet::Util::Profiler.current.length).to eq(0)
    end

    it "registers a profiler if the profiler is not nil" do
      master = Puppet::Server::Master.new({}, {"profiler" => MasterTestProfiler.new})
      expect(Puppet::Util::Profiler.current.length).to eq(1)
    end
  end

  context "multithreaded" do
    before do
     @old_settings = Puppet.settings
    end

    after do
      Puppet.replace_settings_object(@old_settings)
    end

    it 'creates a new Puppet::Server::Settings class for settings' do
      Puppet::Server::Master.new({}, {'multithreaded' => true})
      expect(Puppet.settings).to be_a_kind_of(Puppet::Server::Settings)
    end
  end
end
