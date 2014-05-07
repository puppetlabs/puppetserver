require 'puppet/jvm/master'

describe Puppet::Jvm::Master do
  context "run mode" do
    it "is set to 'master'" do
      master = Puppet::Jvm::Master.new({})
      master.run_mode.should == 'master'
    end
  end
end
