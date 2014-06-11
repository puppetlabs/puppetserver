require 'puppet/ssl'
require 'puppet/ssl/base'
require 'puppet/jvm/certificate'
require 'rspec'

java_import com.puppetlabs.certificate_authority.CertificateAuthority
java_import java.io.FileReader


describe Puppet::Jvm::Certificate do

  java_master_cert = CertificateAuthority.pem_to_cert(
      FileReader.new("test-resources/master-cert-with-dns-alts.pem"))
  master_certificate = Puppet::Jvm::Certificate.new(java_master_cert)

  java_agent_cert = CertificateAuthority.pem_to_cert(
      FileReader.new("test-resources/agent-cert-with-exts.pem"))
  agent_certificate = Puppet::Jvm::Certificate.new(java_agent_cert)

  it 'should return DNS alt names' do
    Set.new(master_certificate.subject_alt_names).should ==
        Set.new(['localhost', 'onefish', 'twofish', 'redfish', 'bluefish'])
  end

  it 'should return Puppet white-listed extensions' do
    exts = agent_certificate.custom_extensions

    exts.select { |ext| ext['oid'] == '1.3.6.1.4.1.34380.1.1.1' }.size.should == 1
    exts.select { |ext| ext['oid'] == '1.3.6.1.4.1.34380.1.1.2' }.size.should == 1
    exts.select { |ext| ext['oid'] == '1.3.6.1.4.1.34380.1.1.3' }.size.should == 1
    exts.select { |ext| ext['oid'] == '1.3.6.1.4.1.34380.1.1.4' }.size.should == 1
  end

  it 'should return the proper subject name' do
    agent_certificate.unmunged_name.should == 'CN=myagent'
  end

  it 'should return the expiration date' do
    agent_certificate.expiration.to_s.should == '2019-06-09 15:57:49 -0700'
  end
end