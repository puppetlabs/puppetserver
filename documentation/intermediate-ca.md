# Intermediate CA support with Puppet Server

## Configuration guide

When Puppet Server or PE is installed it will autogenerate a number of certificates. Regenerating all of these certificates can be a time consuming process, but this problem can be side stepped by generating an intermediate CA certificate that reuses the existing key pair that Puppet Server/PE has already created.

### Configure the Puppet agent `certificate_revocation` checking

Intermediate CA support is incompatible with full chain revocation checking, as Puppet is unable to load multiple CRLs. When an intermediate CA certificate is in use Puppet must have the `certificate_revocation` setting either set to `false`, or `leaf`. By default Puppet will attempt to perform full chain checking and will fail when encountering a non-root CA certificate, so this setting **must** be reconfigured on all Puppet agents before continuing.

    puppet config set --section main certificate_revocation leaf

See [PUP-3788](https://tickets.puppetlabs.com/browse/PUP-3788) for more information on chain revocation checking.

### Generate an intermediate CA certificate request

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

        puts csr.to_s
      end
    end
    Puppet::Application::GenCACSR.new.run

Use this CSR to generate the intermediate CA certificate that Puppet will be using.

### Construct the CA certificate chain

Puppet Server needs to present the full certificate chain to clients in order for the client to authenticate the server. The certificate chain is constructed by concatenating the CA certificates together, starting with the new intermediate CA certificate and descending to the root CA certificate.

    cat puppet-intermediate-certificate.pem \
        org-intermediate-certificate.pem \
         org-root-certificate.pem > ca-bundle.pem

### Verify the Puppet master host certificate against the CA bundle

Before installing the new certificate bundle, verify that the bundle can be used to verify the existing host certificate on the CA server. If this step fails then the CA certificate bundle is invalid and cannot be installed.

    openssl verify -CAfile ca-bundle.pem $(puppet master --configprint hostcert)

### Update the Puppet CA certificate bundle

    cp ca-bundle.pem /etc/puppetlabs/puppet/ssl/ca/ca_crt.pem

### Update the Puppet agent CA certificate bundle

The Puppet agent caches the CA certificate and will not automatically fetch the new CA certificate bundle from the master. This caveat applies to the Puppet CA server itself as well. On the CA server, copy the CA certificate bundle to the local CA certificate path:

    cp /etc/puppetlabs/puppet/ssl/ca/ca_crt.pem /etc/puppetlabs/puppet/ssl/certs/ca.pem

If any Puppet agents have already been created, copy the CA certificate bundle to `/etc/puppetlabs/puppet/ssl/certs/ca.pem`.

Note that due to [PUP-6697](https://tickets.puppetlabs.com/browse/PUP-6697) Puppet agents are unable to download CA certificate bundles and will only save the first certificate in the bundle. Since the first certificate in the bundle will be the Puppet intermediate CA certificate, if the Puppet agent downloads and saves this single CA certificate it will not be able to complete verification of the Puppet Server certificate and will fail to connect.

There may be other settings in the `webserver.conf` file; the other settings should be left alone.

### Restart Puppet Server

Once the new CA certificate is installed, restart pe-puppetserver.

## Troubleshooting

#### The Puppet agent continues to work with an outdated CA certificate

[regenerate-pki]: https://docs.puppet.com/puppet/latest/ssl_regenerate_certificates.html

If a Puppet agent has a copy of the original Puppet root CA certificate it will still be able to authenticate the Puppet Server host certificate. This is intentional behavior on the part of X.509 because the intermediate CA certificate was created with information matching the old root CA certificate. If this behavior is unacceptable then the entire [Puppet PKI will need to be regenerated][regenerate-pki].

#### The Puppet agent only has the Puppet intermediate CA certificate

##### Error message

    Error: Could not request certificate: SSL_connect returned=1 errno=0 state=error: certificate verify failed: [unable to get issuer certificate for /CN=Puppet Enterprise CA generated on <CA server fqdn> at +2017-10-03 00:13:16 +0000]

##### Cause

If the Puppet agent does not have a copy of the CA certificate bundle, when it downloads the CA cert from the Puppet Server it will only get the first certificate in the bundle. This will be the Puppet intermediate CA certificate, and without the rest of the certificate chain Puppet will not be able to validate the certificate.

##### Solution

Copy the full CA certificate bundle from the Puppet Server to `/etc/puppetlabs/puppet/ssl/certs/ca.pem`.

    curl -k https://$(puppet agent --configprint server):8140/puppet-ca/v1/certificate/ca > $(puppet agent --configprint localcacert)

Note that using curl to fetch the CA certificate bundle requires disabling SSL verification and is inherently insecure. If the security of this operation is a concern, an out of band mechanism should be used to copy the CA certificate bundle to agents.

#### The Puppet agent has `certificate_revocation` set to true

##### Error message

    Warning: SSL_connect returned=1 errno=0 state=error: certificate verify failed: [unable to get certificate CRL for /CN=Puppet Enterprise CA generated on <CA server fqdn> at +2017-10-03 00:13:16 +0000]

##### Cause

The default value for `certificate_revocation` is set to true but cannot be used with an intermediate CA. Running Puppet with this setting misconfigured will cause SSL verification to fail.

##### Solution

Configure the Puppet agent to perform only leaf certificate revocation checking.

    puppet config set --section main certificate_revocation leaf

Alternately, configure the Puppet agent to disable certificate revocation checking.

    puppet config set --section main certificate_revocation false

#### The Puppet agent has a CRL with the wrong issuer

##### Error message

    Warning: SSL_connect returned=1 errno=0 state=error: certificate verify failed: [unable to get certificate CRL for /CN=<Puppet Server fqdn]

This error message looks similar to the error produced by an incorrect `certificate_revocation` setting. The difference between the two cases is that if `certificate_revocation` is incorrectly set, the subject/CN will be the subject of the CA certificate, while a CRL with the wrong issuer will generate an error mentioning the hostname of the Puppet Master.

##### Cause

Puppet does not verify that a CRL was issued by a specific CA, so it's possible to distribute and update a CRL whose issuer is not the Puppet CA. In addition the `puppet cert revoke` and `puppet cert clean` will modify a CRL issued by a different CA and save the new CRL with the wrong issuer. Because the issuer of the CRL does not match the issuer of the Puppet Server host Puppet is unable to determine that the loaded CRL is associated with the certificate.

This mismatch can be verified by comparing the issuer of the host certificate and the downloaded CRL

    diff -q <(openssl x509 -in $(puppet agent --configprint hostcert) -noout -issuer) <(openssl crl -in $(puppet agent --configprint hostcrl) -noout -issuer)
    Files /dev/fd/63 and /dev/fd/62 differ

##### Solution

Remediating this issue is tricky because if the wrong CRL has been copied onto the Puppet Server then it's possible for certificate revocation information to be permanently lost. The best way to remediate this issue is to remove the CRL entirely, issue a dummy certificate, and then revoke/clean it to generate a new CRL.

    rm $(puppet agent --configprint hostcrl) $(puppet master --configprint cacrl)
    puppet cert generate revokeme
    puppet cert clean revokeme

    [root@pe-master ~]# puppet cert generate revokeme
    Notice: revokeme has a waiting certificate request
    Notice: Signed certificate request for revokeme
    Notice: Removing file Puppet::SSL::CertificateRequest revokeme at '/etc/puppetlabs/puppet/ssl/ca/requests/revokeme.pem'
    Notice: Removing file Puppet::SSL::CertificateRequest revokeme at '/etc/puppetlabs/puppet/ssl/certificate_requests/revokeme.pem'
    [root@pe-master ~]# puppet cert clean revokeme
    Notice: Revoked certificate with serial 7
    Notice: Removing file Puppet::SSL::Certificate revokeme at '/etc/puppetlabs/puppet/ssl/ca/signed/revokeme.pem'
    Notice: Removing file Puppet::SSL::Certificate revokeme at '/etc/puppetlabs/puppet/ssl/certs/revokeme.pem'
    Notice: Removing file Puppet::SSL::Key revokeme at '/etc/puppetlabs/puppet/ssl/private_keys/revokeme.pem'

#### The Puppet CA cannot generate certificates when the CA bundle has been installed

##### Error message

    [root@pe- ~]# puppet cert generate failtosign
    Error: The certificate retrieved from the master does not match the agent's private key.
    Certificate fingerprint: 10:0C:DF:B0:41:91:46:BF:1B:A8:F4:F5:44:88:1D:99:F3:B3:AE:3C:3A:E4:24:66:FB:50:CB:4A:20:FE:4F:6D
    To fix this, remove the certificate from both the master and the agent and then start a puppet run, which will automatically regenerate a certificate.
    On the master:
      puppet cert clean pe-20173nightly-master.puppetdebug.vlan
    On the agent:
      1a. On most platforms: find /etc/puppetlabs/puppet/ssl -name pe-20173nightly-master.puppetdebug.vlan.pem -delete
      1b. On Windows: del "\etc\puppetlabs\puppet\ssl\certs\pe-20173nightly-master.puppetdebug.vlan.pem" /f
      2. puppet agent -t

##### Cause

The `puppet cert generate` command should use the first certificate in the `cacert` file, but unintentionally uses the `localcacert` file. If the first certificate in this file is not the Puppet intermediate CA certificate Puppet will load the CA key and a mismatched CA certificate, and will fail with a misleading error message. The logic for verifying that the key and certificate match is shared with the agent key/certificate verification and will report that the agent key and certificate are mismatched when the CA certificate and key are mismatched.

See [PUP-7985](https://tickets.puppetlabs.com/browse/PUP-7985) for more details.

##### Solution

Copy the CA certificate bundle from the `cacert` file to the `localcacert` file.

    cp $(puppet master --configprint cacert) $(puppet agent --configprint localcacert)
