require 'puppet/ssl/base'
require 'puppet/ssl/certificate'
require 'puppet/ssl/oids'
require 'puppet/jvm'
require 'java'

java_import com.puppetlabs.certificate_authority.CertificateAuthority
java_import java.security.cert.X509Certificate

class Puppet::Jvm::Certificate < Puppet::SSL::Certificate
  def initialize(java_cert)
    unless java_cert.is_a? Java::JavaSecurityCert::X509Certificate
      raise(ArgumentError, "java_cert must be a Java X509Certificate.")
    end

    @java_cert = java_cert
  end

  def to_s
    @java_cert.to_s
  end

  def subject_alt_names
    alt_names_list = @java_cert.getSubjectAlternativeNames

    if alt_names_list
      alt_names_list.map { |name_arr| name_arr[1] }
    end
  end

  def expiration
    Time.at(@java_cert.getNotAfter.getTime / 1000).utc
  end

  def unmunged_name
    CertificateAuthority.get_cn_from_x500_principal(@java_cert.getSubjectX500Principal)
  end

  def custom_extensions
    exts = CertificateAuthority.get_extensions(@java_cert)

    valid_oids = exts.select do |oid,value|
      Puppet::SSL::Oids.subtree_of?('ppRegCertExt', oid) or
        Puppet::SSL::Oids.subtree_of?('ppPrivCertExt', oid)
    end

    valid_oids.collect do |oid,value|
      {'oid' => get_oid_name(oid), 'value' => value}
    end
  end

  private

  def get_oid_name(oid)
    found_oid_desc = Puppet::SSL::Oids::PUPPET_OIDS.select { |oid_desc|
      oid_desc[0] == oid
    }[0]

    unless found_oid_desc.nil?
      found_oid_desc[1]
    else
      oid
    end
  end
end