require 'spec_helper'

require 'puppet/ssl'
require 'puppet/ssl/base'
require 'puppet/server/certificate'
require 'rspec'

java_import com.puppetlabs.ssl_utils.SSLUtils
java_import java.io.FileReader


describe Puppet::Server::Certificate do

  java_master_cert = SSLUtils.pem_to_cert(
      FileReader.new("spec/fixtures/master-cert-with-dns-alts.pem"))
  master_certificate = Puppet::Server::Certificate.new(java_master_cert)

  java_agent_cert = SSLUtils.pem_to_cert(
      FileReader.new("spec/fixtures/agent-cert-with-exts.pem"))
  agent_certificate = Puppet::Server::Certificate.new(java_agent_cert)

  it 'should return DNS alt names' do
    Set.new(master_certificate.subject_alt_names).should ==
        Set.new(['localhost', 'onefish', 'twofish', 'redfish', 'bluefish'])
  end

  it 'should return Puppet white-listed extensions' do
    exts = agent_certificate.custom_extensions

    exts.find { |ext|
      ext['oid'] == 'pp_uuid' }['value'].should == "ED803750-E3C7-44F5-BB08-41A04433FE2E"

    exts.find { |ext|
      ext['oid'] == 'pp_instance_id' }['value'].should == "1234567890"

    exts.find { |ext|
      ext['oid'] == 'pp_image_name' }['value'].should == "my_ami_image"

    exts.find { |ext|
      ext['oid'] == 'pp_preshared_key' }['value'].should == "342thbjkt82094y0uthhor289jnqthpc2290"
  end

  it 'should return the proper subject name' do
    agent_certificate.unmunged_name.should == 'myagent'
  end

  it 'should return the expiration date' do
    agent_certificate.expiration.to_s.should == '2019-06-09 22:57:49 UTC'
  end
end
