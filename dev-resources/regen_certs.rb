require 'openssl'

module PuppetSpec
  module SSL

    PRIVATE_KEY_LENGTH = 2048
    FIVE_YEARS = 5 * 365 * 24 * 60 * 60
    CA_EXTENSIONS = [
      ["basicConstraints", "CA:TRUE", true],
      ["keyUsage", "keyCertSign, cRLSign", true],
      ["subjectKeyIdentifier", "hash", false],
      ["authorityKeyIdentifier", "keyid:always", false]
    ]
    NODE_EXTENSIONS = [
      ["keyUsage", "digitalSignature,keyEncipherment", true],
      ["subjectKeyIdentifier", "hash", false]
    ]
    DEFAULT_SIGNING_DIGEST = OpenSSL::Digest::SHA256.new
    DEFAULT_REVOCATION_REASON = OpenSSL::OCSP::REVOKED_STATUS_KEYCOMPROMISE

    def self.create_private_key(length = PRIVATE_KEY_LENGTH)
      OpenSSL::PKey::RSA.new(length)
    end

    def self.self_signed_ca(key, name)
      cert = OpenSSL::X509::Certificate.new

      cert.public_key = key.public_key
      cert.subject = OpenSSL::X509::Name.parse(name)
      cert.issuer = cert.subject
      cert.version = 2
      cert.serial = rand(2**128)

      not_before = just_now
      cert.not_before = not_before
      cert.not_after = not_before + FIVE_YEARS

      ext_factory = extension_factory_for(cert, cert)
      CA_EXTENSIONS.each do |ext|
        extension = ext_factory.create_extension(*ext)
        cert.add_extension(extension)
      end

      cert.sign(key, DEFAULT_SIGNING_DIGEST)

      cert
    end

    def self.create_csr(key, name)
      csr = OpenSSL::X509::Request.new

      csr.public_key = key.public_key
      csr.subject = OpenSSL::X509::Name.parse(name)
      csr.version = 2
      csr.sign(key, DEFAULT_SIGNING_DIGEST)

      csr
    end

    def self.sign(ca_key, ca_cert, csr, extensions = NODE_EXTENSIONS)
      cert = OpenSSL::X509::Certificate.new

      cert.public_key = csr.public_key
      cert.subject = csr.subject
      cert.issuer = ca_cert.subject
      cert.version = 2
      cert.serial = rand(2**128)

      not_before = just_now
      cert.not_before = not_before
      cert.not_after = not_before + FIVE_YEARS

      ext_factory = extension_factory_for(ca_cert, cert)
      extensions.each do |ext|
        extension = ext_factory.create_extension(*ext)
        cert.add_extension(extension)
      end

      cert.sign(ca_key, DEFAULT_SIGNING_DIGEST)

      cert
    end

   private

    def self.just_now
      Time.now - 1
    end

    def self.extension_factory_for(ca, cert = nil)
      ef = OpenSSL::X509::ExtensionFactory.new
      ef.issuer_certificate  = ca
      ef.subject_certificate = cert if cert

      ef
    end

    def self.bundle(*items)
      items.map {|i| EXPLANATORY_TEXT + i.to_pem }.join("\n")
    end
  end
end

ca_key = PuppetSpec::SSL.create_private_key
ca_cert = PuppetSpec::SSL.self_signed_ca(ca_key, "/CN=Puppet CA: swanson.hsd1.or.comcast.net")

host_key = PuppetSpec::SSL.create_private_key
host_csr = PuppetSpec::SSL.create_csr(host_key, "/CN=localhost")
host_cert = PuppetSpec::SSL.sign(ca_key, ca_cert, host_csr)

File.open('ca.pem', 'w') do |f|
  f.puts(ca_cert)
end
File.open('localhost_cert.pem', 'w') do |f|
  f.puts(host_cert)
end
File.open('localhost_key.pem', 'w') do |f|
  f.puts(host_key)
end
