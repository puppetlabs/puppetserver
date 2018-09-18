---
layout: default
title: "Puppet Server: Intermediate CA Configuration"
canonical: "/puppetserver/latest/intermediate_ca_configuration.html"
---

Puppet Server automatically generates a number of certificate authority (CA) certificates upon installation. However, you might need to regenerate these certificates under certain circumstances, such as moving a Puppet Server to a different network in your infrastructure or recovering from an unforeseen security vulnerability that makes your existing certificates untrustworthy.

Regenerating all of these certificates can be a time-consuming process. You can reduce the time spent regenerating certificates by generating an **intermediate CA certificate** that reuses the key pair created by Puppet Server.

## Generate an intermediate CA certificate request

You can generate an intermediate CA certificate on Puppet Server by creating a certificate signing request (CSR).

1.  Save the following Ruby script to a file. For the purposes of this task, we'll name it `csrgen.rb`.

    ``` 
    ruby
    #!/opt/puppetlabs/puppet/bin/ruby
    require 'puppet'
    require 'openssl'

    class Puppet::Application::GenCACSR < Puppet::Application
      run_mode :master

      def main
        root_cert = OpenSSL::X509::Certificate.new(File.read(Puppet[:cacert]))
        ca_key = OpenSSL::PKey::RSA.new(File.read(Puppet[:cakey]))

        csr = OpenSSL::X509::Request.new
        csr.version = 0
        csr.subject = root_cert.subject
        csr.public_key = root_cert.public_key

        ef = OpenSSL::X509::ExtensionFactory.new

        ext_req = OpenSSL::X509::Attribute.new("extReq",
          OpenSSL::ASN1::Set([OpenSSL::ASN1::Sequence([
            ef.create_extension("keyUsage", "cRLSign, keyCertSign", true),
            ef.create_extension("basicConstraints", "CA:TRUE", true)])]))

        csr.add_attribute(ext_req)
        csr.sign(ca_key, OpenSSL::Digest::SHA256.new)

        File.open("req.pem", "w") do |f|
          f.write(csr.to_s)
        end
        puts csr.to_s
      end
    end
    Puppet::Application::GenCACSR.new.run
    ```

2.  Make the script executable by running `chmod u+x csrgen.rb`.

3.  Run `./csrgen.rb`.

Running this command writes CSR to `req.pem` and prints the same CSR to stdout.

## Submit the generated CSR for signing

Take the CSR that was just generated and submit it for signing by your PKI. The signing procedure depends on the public key infrastructure (PKI) that your organization deploys; ask your PKI administrators for more information.

## Construct the CA certificate chain

Puppet Server needs to present the full certificate chain to clients so the client can authenticate the server. You construct the certificate chain by concatenating the CA certificates, starting with the new intermediate CA certificate and descending to the root CA certificate.

In the following command:

-   `puppet-intermediate-certificate.pem` is the CA certificate that was issued by your organization's PKI for Puppet and will be used as the issuing certificate for your Puppet infrastructure.
-   `org-intermediate-certificate.pem` and `org-root-certificate.pem` represent the CA certificates that your organization uses, though the number of CA certificates depend on the structure of your organization's PKI.

To concatenate the certificate chain, run this command, using the filenames of your organization's certificates:

```
cat puppet-intermediate-certificate.pem \
  org-intermediate-certificate.pem \
  org-root-certificate.pem > ca-bundle.pem
```

## Verify and install the Server certificate chain

Before installing the new certificate chain, confirm that you can use the chain to verify the existing host certificate on the CA server.

1.  Run this command against [the chain you generated](#construct-the-ca-certificate-chain):

    ```
    openssl verify -CAfile ca-bundle.pem $(puppet master --configprint hostcert)
    ```

    If this step fails, then the CA certificate bundle is invalid and cannot be installed. Try recreating the bundle, or check that the certificates used to create the bundle are correct.

    If this step succeeds, you can continue by updating Puppet Server's CA certificate bundle.

2. Back up the old root CA certificate.

    ```
    cp /etc/puppetlabs/puppet/ssl/ca/ca_crt.pem /etc/puppetlabs/puppet/ssl/ca/ca_crt.pem.old
    ```

3.  Import the certificate chain to the Puppet CA and agent CA certificate locations.

    ```
    puppetserver ca import --cert-bundle <intermediate+root cert file> --crl-chain <intermediate+root CRL file> --private-key <intermediate cert’s private key>
    ```

4.  Once the new CA certificate is installed, restart the `puppetserver` service.


