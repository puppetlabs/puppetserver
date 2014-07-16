require 'spec_helper'

require 'puppet/jvm/master'
require 'puppet/jvm/jvm_profiler'

describe Puppet::Jvm::Master do
  context "run mode" do
    it "is set to 'master'" do
      master = Puppet::Jvm::Master.new({}, nil)
      master.run_mode.should == 'master'
    end
  end

  context "puppet version" do
    it "returns the correct puppet version number" do
      master = Puppet::Jvm::Master.new({}, nil)
      master.puppetVersion.should == '3.6.2'
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
      master = Puppet::Jvm::Master.new({}, nil)
      Puppet::Util::Profiler.current.length.should == 0
    end

    it "registers a profiler if the profiler is not nil" do
      master = Puppet::Jvm::Master.new({}, Puppet::Jvm::JvmProfiler.new(MasterTestProfiler.new))
      Puppet::Util::Profiler.current.length.should == 1
    end
  end

end
