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

    def self.self_signed_ca(key, name, serial = rand(2**128))
      cert = OpenSSL::X509::Certificate.new

      cert.public_key = key.public_key
      cert.subject = OpenSSL::X509::Name.parse(name)
      cert.issuer = cert.subject
      cert.version = 2
      cert.serial = serial

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

	def self.create_crl(ca_cert, ca_key, revoked_cert, serial)
	  crl = OpenSSL::X509::CRL.new

	  crl.issuer = ca_cert.issuer
	  crl.version = 1
	  last_update = just_now
	  crl.last_update = last_update
	  crl.next_update = last_update + FIVE_YEARS

	  revoked = OpenSSL::X509::Revoked.new
	  revoked.serial = revoked_cert.serial
	  revoked.time = just_now
	  crl.add_revoked(revoked)

	  crlnum = OpenSSL::ASN1::Integer(serial)
	  crl.add_extension(OpenSSL::X509::Extension.new('crlNumber', crlnum))

	  ef = OpenSSL::X509::ExtensionFactory.new
	  ef.issuer_certificate = ca_cert
	  ef.crl = crl
	  crl.add_extension(ef.create_extension('authorityKeyIdentifier', 'keyid:always'))

	  crl.sign(ca_key, DEFAULT_SIGNING_DIGEST)

	  crl
	end

    def self.create_csr(name, key = PuppetSpec::SSL.create_private_key)
      csr = OpenSSL::X509::Request.new

      csr.public_key = key.public_key
      csr.subject = OpenSSL::X509::Name.parse(name)
      csr.version = 2
      csr.sign(key, DEFAULT_SIGNING_DIGEST)

      csr
    end

    def self.sign(ca_key, ca_cert, csr, serial = rand(2**128), extensions = NODE_EXTENSIONS)
      cert = OpenSSL::X509::Certificate.new

      cert.public_key = csr.public_key
      cert.subject = csr.subject
      cert.issuer = ca_cert.subject
      cert.version = 2
      cert.serial = serial

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

puts 'Regenerating certificates for puppetlabs.puppetserver.ruby.http-client-test ...'

ca_key = PuppetSpec::SSL.create_private_key
ca_cert = PuppetSpec::SSL.self_signed_ca(ca_key, "/CN=Puppet CA: swanson.hsd1.or.comcast.net")

host_key = PuppetSpec::SSL.create_private_key
host_csr = PuppetSpec::SSL.create_csr("/CN=localhost", host_key)
host_cert = PuppetSpec::SSL.sign(ca_key, ca_cert, host_csr)

http_client_test_dir = "#{__dir__}/puppetlabs/puppetserver/ruby/http_client_test"

File.open("#{http_client_test_dir}/ca.pem", 'w') do |f|
  f.puts(ca_cert)
end
File.open("#{http_client_test_dir}/localhost_cert.pem", 'w') do |f|
  f.puts(host_cert)
end
File.open("#{http_client_test_dir}/localhost_key.pem", 'w') do |f|
  f.puts(host_key)
end

puts 'Regenerating certificates for puppetlabs.puppetserver.certificate-authority-test ...'

ca_key = PuppetSpec::SSL.create_private_key
ca_cert = PuppetSpec::SSL.self_signed_ca(ca_key, "/CN=Puppet CA: localhost", 1)
test_agent_csr = PuppetSpec::SSL.create_csr("/CN=test-agent")
localhost_csr = PuppetSpec::SSL.create_csr("/CN=localhost")
localhost_cert = PuppetSpec::SSL.sign(ca_key, ca_cert, localhost_csr, 2)
test_cert_csr = PuppetSpec::SSL.create_csr("/CN=test_cert")
test_cert = PuppetSpec::SSL.sign(ca_key, ca_cert, test_cert_csr, 3)
revoked_agent_csr = PuppetSpec::SSL.create_csr("/CN=revoked-agent")
revoked_agent_cert = PuppetSpec::SSL.sign(ca_key, ca_cert, revoked_agent_csr, 4)
ca_crl = PuppetSpec::SSL.create_crl(ca_cert, ca_key, revoked_agent_cert, 1)

ca_dir = "#{__dir__}/puppetlabs/puppetserver/certificate_authority_test/master/conf/ca"

File.open("#{ca_dir}/ca_key.pem", 'w') do |f|
  f.puts(ca_key)
end
File.open("#{ca_dir}/ca_pub.pem", 'w') do |f|
  f.puts(ca_key.public_key)
end
File.open("#{ca_dir}/ca_crt.pem", 'w') do |f|
  f.puts(ca_cert)
end
File.open("#{ca_dir}/requests/test-agent.pem", 'w') do |f|
  f.puts(test_agent_csr)
end
File.open("#{ca_dir}/signed/localhost.pem", 'w') do |f|
  f.puts(localhost_cert)
end
File.open("#{ca_dir}/signed/test_cert.pem", 'w') do |f|
  f.puts(test_cert)
end
File.open("#{ca_dir}/signed/revoked-agent.pem", 'w') do |f|
  f.puts(revoked_agent_cert)
end
File.open("#{ca_dir}/ca_crl.pem", 'w') do |f|
  f.puts(ca_crl)
end
File.open("#{ca_dir}/serial", 'w') do |f|
  f.write('0005')
end
File.open("#{ca_dir}/infra_crl.pem", 'w') { |f| f.truncate(0) }
File.open("#{ca_dir}/infra_serials", 'w') { |f| f.truncate(0) }
File.open("#{ca_dir}/inventory.txt", 'w') do |f|
  for cert in [ ca_cert, localhost_cert, test_cert, revoked_agent_cert ] do
    serial_hex = "0x#{cert.serial.to_s(16).rjust(4, '0')}"
    not_before = cert.not_before.strftime('%Y-%m-%dT%H:%M:%SUTC')
    not_after = cert.not_after.strftime('%Y-%m-%dT%H:%M:%SUTC')
    subject = cert.subject

    f.puts("#{serial_hex} #{not_before} #{not_after} #{subject}")
  end
end

puts "Done."
