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

    it "is set to 'server'" do
      expect(subject).to eq('server')
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

  context "#check_ca_dir_for_deprecation_warning" do
    before do
      @master = Puppet::Server::Master.new({}, {})
      @ssldir = Puppet[:ssldir]
      @cadir = File.join(@ssldir, 'ca')
    end

    it "warns when there is a directory in puppet's ssl dir" do
      expect(File).to receive(:exist?).with(@cadir).and_return(true)
      expect(File).to receive(:symlink?).with(@cadir).and_return(false)
      expect(@master).to receive(:log_ca_migration_warning)
      @master.check_cadir_for_deprecation_warning
    end

    it "warns when the symlink target is in puppet's ssl dir" do
      expect(File).to receive(:exist?).with(@cadir).and_return(true)
      expect(File).to receive(:symlink?).with(@cadir).and_return(true)
      expect(File).to receive(:readlink).with(@cadir).and_return(File.join(@ssldir, 'someotherdirinssldir'))
      expect(@master).to receive(:log_ca_migration_warning)
      @master.check_cadir_for_deprecation_warning
    end

    it "does not warn when there is no ca directory in puppet's ssl dir" do
      expect(File).to receive(:exist?).with(@cadir).and_return(false)
      expect(@master).to_not receive(:log_ca_migration_warning)
      @master.check_cadir_for_deprecation_warning
    end

    it "does not warn when there is a symlink pointing outside puppet's ssl dir" do
      expect(File).to receive(:exist?).with(@cadir).and_return(true)
      expect(File).to receive(:symlink?).with(@cadir).and_return(true)
      expect(File).to receive(:readlink).with(@cadir).and_return('/I/am/a/dir/outside/of/puppets/ssl/dir')
      expect(@master).to_not receive(:log_ca_migration_warning)
      @master.check_cadir_for_deprecation_warning
    end
  end
end
