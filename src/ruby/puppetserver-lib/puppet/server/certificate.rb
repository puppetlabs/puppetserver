require 'puppet/ssl/base'
require 'puppet/ssl/certificate'
require 'puppet/ssl/oids'
require 'puppet/server'
require 'java'

java_import com.puppetlabs.ssl_utils.SSLUtils
java_import com.puppetlabs.ssl_utils.ExtensionsUtils
java_import java.security.cert.X509Certificate

class Puppet::Server::Certificate < Puppet::SSL::Certificate
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
    SSLUtils.get_cn_from_x500_principal(@java_cert.getSubjectX500Principal)
  end

  def custom_extensions
    exts = ExtensionsUtils.get_extension_list(@java_cert)

    if exts.nil?
      []
    else
      valid_oids = exts.select do |ext|
        subtree_of?(get_name_from_oid('ppRegCertExt'), ext['oid']) or
            subtree_of?(get_name_from_oid('ppPrivCertExt'), ext['oid'])
      end

      valid_oids.collect do |ext|
        {'oid' => get_oid_name(ext['oid']), 'value' => ext['value']}
      end
    end
  end

  private

  def get_oid_name(oid)
    found_oid_desc = Puppet::Server::PuppetConfig.oid_defns.select { |oid_desc|
      oid_desc[0] == oid
    }[0]

    if found_oid_desc.nil?
      oid
    else
      found_oid_desc[1]
    end
  end

  def get_name_from_oid(short_name)
    found_oid_desc = Puppet::Server::PuppetConfig.oid_defns.select { |oid_desc|
      oid_desc[1] == short_name
    }[0]

    unless found_oid_desc.nil?
      found_oid_desc[0]
    end
  end

  def subtree_of?(first, second)
    if first == second
      false
    else
      second.start_with? first
    end
  end
end
