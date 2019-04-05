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
    expect(Set.new(master_certificate.subject_alt_names)).to eq(
        Set.new(['localhost', 'onefish', 'twofish', 'redfish', 'bluefish'])
    )
  end

  it 'should return Puppet white-listed extensions' do
    Puppet::Server::PuppetConfig.initialize_puppet({})
    exts = agent_certificate.custom_extensions

    expect(exts.find { |ext|
      ext['oid'] == 'pp_uuid' }['value']).to eq("ED803750-E3C7-44F5-BB08-41A04433FE2E")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_instance_id' }['value']).to eq("thisisanid")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_image_name' }['value']).to eq("my_ami_image")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_preshared_key' }['value']).to eq("342thbjkt82094y0uthhor289jnqthpc2290")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_cost_center' }['value']).to eq("center")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_product' }['value']).to eq("product")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_project' }['value']).to eq("project")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_application' }['value']).to eq("application")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_service' }['value']).to eq("service")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_employee' }['value']).to eq("employee")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_created_by' }['value']).to eq("created")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_environment' }['value']).to eq("environment")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_role' }['value']).to eq("role")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_software_version' }['value']).to eq("version")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_department' }['value']).to eq("deparment")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_cluster' }['value']).to eq("cluster")

    expect(exts.find { |ext|
      ext['oid'] == 'pp_provisioner' }['value']).to eq("provisioner")
  end

  it 'should return the proper subject name' do
    expect(agent_certificate.unmunged_name).to eq('firehose-agent')
  end

  it 'should return the expiration date' do
    expect(agent_certificate.expiration.to_s).to eq('2020-03-11 21:44:28 UTC')
  end
end
