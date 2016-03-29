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
    Puppet::Server::PuppetConfig.initialize_puppet({})
    exts = agent_certificate.custom_extensions

    exts.find { |ext|
      ext['oid'] == 'pp_uuid' }['value'].should == "ED803750-E3C7-44F5-BB08-41A04433FE2E"

    exts.find { |ext|
      ext['oid'] == 'pp_instance_id' }['value'].should == "thisisanid"

    exts.find { |ext|
      ext['oid'] == 'pp_image_name' }['value'].should == "my_ami_image"

    exts.find { |ext|
      ext['oid'] == 'pp_preshared_key' }['value'].should == "342thbjkt82094y0uthhor289jnqthpc2290"

    exts.find { |ext|
      ext['oid'] == 'pp_cost_center' }['value'].should == "center"

    exts.find { |ext|
      ext['oid'] == 'pp_product' }['value'].should == "product"

    exts.find { |ext|
      ext['oid'] == 'pp_project' }['value'].should == "project"

    exts.find { |ext|
      ext['oid'] == 'pp_application' }['value'].should == "application"

    exts.find { |ext|
      ext['oid'] == 'pp_service' }['value'].should == "service"

    exts.find { |ext|
      ext['oid'] == 'pp_employee' }['value'].should == "employee"

    exts.find { |ext|
      ext['oid'] == 'pp_created_by' }['value'].should == "created"

    exts.find { |ext|
      ext['oid'] == 'pp_environment' }['value'].should == "environment"

    exts.find { |ext|
      ext['oid'] == 'pp_role' }['value'].should == "role"

    exts.find { |ext|
      ext['oid'] == 'pp_software_version' }['value'].should == "version"

    exts.find { |ext|
      ext['oid'] == 'pp_department' }['value'].should == "deparment"

    exts.find { |ext|
      ext['oid'] == 'pp_cluster' }['value'].should == "cluster"

    exts.find { |ext|
      ext['oid'] == 'pp_provisioner' }['value'].should == "provisioner"
  end

  it 'should return the proper subject name' do
    agent_certificate.unmunged_name.should == 'firehose-agent'
  end

  it 'should return the expiration date' do
    agent_certificate.expiration.to_s.should == '2020-03-11 21:44:28 UTC'
  end
end
