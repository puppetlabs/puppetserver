---
layout: default
title: "Puppet Server: Intermediate CA Configuration"
canonical: "/puppetserver/latest/intermediate_ca_configuration.html"
---

Puppet Server automatically generates a number of certificate authority (CA) certificates upon installation. However, you might need to regenerate these certificates under certain circumstances, such as moving a Puppet Server to a different network in your infrastructure or recovering from an unforeseen security vulnerability that makes your existing certificates untrustworthy.

Regenerating all of these certificates can be a time-consuming process. You can reduce the time spent regenerating certificates by generating an **intermediate CA certificate** that reuses the key pair created by Puppet Server.

## Configure Puppet agent `certificate_revocation` checking

Puppet cannot load multiple certificate revocation lists (CRLs). Because Puppet attempts to perform full-chain checking by default, it will fail when encountering a non-root CA certificate, such as an intermediate CA certificate.

Therefore, when using an intermediate CA certificate you **must** set the [`certificate_revocation`](https://docs.puppet.com/puppet/latest/configuration.html#certificaterevocation) setting in the `puppet.conf` file on all Puppet managed nodes to either `false` or `leaf` before configuring intermediate CA certificates. This prevents agents from attempting to perform a chain check that it cannot complete.

You can configure this setting with the `puppet config set` command:

```
puppet config set --section main certificate_revocation leaf
```

> **Note:** Puppet's incomplete support for chained CRLs is a [known limitation](https://tickets.puppetlabs.com/browse/PUP-3788). For details, see [the Puppet documentation](https://docs.puppet.com/puppet/latest/config_ssl_external_ca.html#option-2-puppet-server-functioning-as-an-intermediate-ca).

## Generate an intermediate CA certificate request

You can generate an intermediate CA certificate on Puppet Server by creating a certificate signing request (CSR).

1.  Save the following Ruby script to a file. For the purposes of this task, we'll name it `csrgen.rb`.

    ``` ruby
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

3.  Copy the certificate chain to the Puppet CA and agent CA certificate locations.

    ```
    cp ca-bundle.pem /etc/puppetlabs/puppet/ssl/ca/ca_crt.pem
    cp ca-bundle.pem /etc/puppetlabs/puppet/ssl/certs/ca.pem
    ```

4.  Once the new CA certificate is installed, restart the `puppetserver` service.

## Install the certificate chain on the agents

Each Puppet agent caches the CA certificate and doesn't automatically fetch the new CA certificate bundle from the master. If you already have Puppet agents, copy the CA certificate bundle to `/etc/puppetlabs/puppet/ssl/certs/ca.pem` on every Puppet agent that was created before the new CA certificate was generated.

> **Note:** Due to [a known issue](https://tickets.puppetlabs.com/browse/PUP-6697), Puppet agents cannot download CA certificate bundles and will only save the first certificate in the bundle. Since the first certificate in the bundle created by this process is the Puppet intermediate CA certificate, Puppet agents that download and save this single certificate cannot verify Puppet Server's certificate and will fail to connect.

## Troubleshooting

### Puppet agent continues to work with an outdated CA certificate

[regenerate-pki]: https://docs.puppet.com/puppet/latest/ssl_regenerate_certificates.html

If a Puppet agent has a copy of the original Puppet root CA certificate, it can still authenticate the Puppet Server host certificate. This is intentional behavior on the part of X.509, because the intermediate CA certificate was created with information matching the old root CA certificate.

If this behavior is unacceptable, you must [regenerate the entire Puppet PKI][regenerate-pki].

### Puppet agent only has the Puppet intermediate CA certificate

#### Error message

```
[root@pe-agent ~]# puppet agent -t
Info: Caching certificate for pe-agent.puppetdebug.vlan
Error: Could not request certificate: SSL_connect returned=1 errno=0 state=error: certificate verify failed: [unable to get issuer certificate for /CN=Puppet Enterprise CA generated on <CA server fqdn> at +2017-10-03 00:13:16 +0000]
```

#### Cause

If the Puppet agent does not have a copy of the CA certificate bundle, when it downloads the CA cert from the Puppet Server, it receives only the first certificate in the bundle. In an intermediate CA certificate configuration, that certificate will be the Puppet intermediate CA certificate, and without the rest of the certificate chain Puppet will not be able to validate the certificate.

#### Solution

Copy the full CA certificate bundle from the Puppet Server to `/etc/puppetlabs/puppet/ssl/certs/ca.pem` on the agent.

If the security of this operation is a concern, use an out-of-band mechanism to copy the CA certificate bundle to agents.

Otherwise, you can use `curl` without SSL verification to fetch the certificate, which is inherently insecure.

```
curl -k https://$(puppet agent --configprint server):8140/puppet-ca/v1/certificate/ca > $(puppet agent --configprint localcacert)
```

### Puppet agent has `certificate_revocation` set to true

#### Error message

```
[root@pe-agent vagrant]# puppet agent -t
Warning: SSL_connect returned=1 errno=0 state=error: certificate verify failed: [unable to get certificate CRL for /CN=Puppet Enterprise CA generated on <CA server fqdn> at +2017-10-03 00:13:16 +0000]
```

This error message looks similar to the error produced by a CRL with the wrong issuer. The difference between the two cases is that if `certificate_revocation` is incorrectly set, the subject/CN will be the subject of the CA certificate, while a CRL with the wrong issuer will generate an error mentioning the hostname of the Puppet Master.

#### Cause

The default value for `certificate_revocation` is true, but running Puppet with this default setting with an intermediate CA causes SSL verification to fail.

#### Solution

Configure the Puppet agent to perform only leaf certificate revocation checking by setting `certificate_revocation` to `leaf`.

```
puppet config set --section main certificate_revocation leaf
```

As an insecure alternative, you can configure the Puppet agent to disable certificate revocation checking by setting `certificate_revocation` to `false`. However, this prevents Puppet Server from successfully revoking certificates, which can open your infrastructure to attacks from agents that have revoked credentials.

```
puppet config set --section main certificate_revocation false
```

### Puppet agent has a CRL with the wrong issuer

#### Error message

```
Warning: SSL_connect returned=1 errno=0 state=error: certificate verify failed: [unable to get certificate CRL for /CN=<Puppet Server fqdn>]
```

This error message looks similar to the error produced by an incorrect `certificate_revocation` setting. The difference between the two cases is that if `certificate_revocation` is incorrectly set, the subject/CN will be the subject of the CA certificate, while a CRL with the wrong issuer will generate an error mentioning the hostname of the Puppet Master.

#### Cause

Puppet does not verify that a CRL was issued by a specific CA, so it's possible to distribute and update a CRL whose issuer is not the Puppet CA. In addition, the `puppet cert revoke` and `puppet cert clean` commands will modify a CRL issued by a different CA and save the new CRL with the wrong issuer.

Because the issuer of the CRL does not match the issuer of the Puppet Server host, Puppet is unable to determine that the loaded CRL is associated with the certificate. This mismatch can be verified by comparing the issuer of the host certificate and the downloaded CRL.

```
diff -q <(openssl x509 -in $(puppet agent --configprint hostcert) -noout -issuer) <(openssl crl -in $(puppet agent --configprint hostcrl) -noout -issuer)
```

If this command reports a difference, you have mismatched CRL issuers.

```
Files /dev/fd/63 and /dev/fd/62 differ
```

#### Solution

If the wrong CRL has been copied onto the Puppet Server, certificate revocation information might be permanently lost. Recovering the original CRL data will vary based on the site configuration and is out of the scope of this document.

If it is acceptable to reset revocation information in your environment you can remediate this issue with the following:

1.  Remove the CRL entirely.

    ```
    rm $(puppet agent --configprint hostcrl) $(puppet master --configprint cacrl)
    ```

2.  Issue a dummy certificate.

    ```
    puppet cert generate revokeme
    ```

    The output should indicate that a certificate request was generated and signed.

    ```
    [root@pe-master ~]# puppet cert generate revokeme
    Notice: revokeme has a waiting certificate request
    Notice: Signed certificate request for revokeme
    Notice: Removing file Puppet::SSL::CertificateRequest revokeme at '/etc/puppetlabs/puppet/ssl/ca/requests/revokeme.pem'
    Notice: Removing file Puppet::SSL::CertificateRequest revokeme at '/etc/puppetlabs/puppet/ssl/certificate_requests/revokeme.pem'
    ```

3.  Generate a new CRL by revoking the dummy certificate.

    ```
    puppet cert clean revokeme
    ```

    The output should indicate that the certificate was revoked and removed.

    ```
    [root@pe-master ~]# puppet cert clean revokeme
    Notice: Revoked certificate with serial 7
    Notice: Removing file Puppet::SSL::Certificate revokeme at '/etc/puppetlabs/puppet/ssl/ca/signed/revokeme.pem'
    Notice: Removing file Puppet::SSL::Certificate revokeme at '/etc/puppetlabs/puppet/ssl/certs/revokeme.pem'
    Notice: Removing file Puppet::SSL::Key revokeme at '/etc/puppetlabs/puppet/ssl/private_keys/revokeme.pem'
    ```


### Puppet CA cannot generate certificates when the CA bundle has been installed

#### Error message

```
[root@pe-master ~]# puppet cert generate failtosign
Error: The certificate retrieved from the master does not match the agent's private key.
Certificate fingerprint: 10:0C:DF:B0:41:91:46:BF:1B:A8:F4:F5:44:88:1D:99:F3:B3:AE:3C:3A:E4:24:66:FB:50:CB:4A:20:FE:4F:6D
To fix this, remove the certificate from both the master and the agent and then start a puppet run, which will automatically regenerate a certificate.
On the master:
  puppet cert clean pe-master.puppetdebug.vlan
On the agent:
  1a. On most platforms: find /etc/puppetlabs/puppet/ssl -name pe-master.puppetdebug.vlan.pem -delete
  1b. On Windows: del "\etc\puppetlabs\puppet\ssl\certs\pe-master.puppetdebug.vlan.pem" /f
  2. puppet agent -t
```

#### Cause

The `puppet cert generate` command should use the first certificate in the `cacert` file, but unintentionally uses the `localcacert` file. This is a [known issue](https://tickets.puppetlabs.com/browse/PUP-7985).

If the first certificate in this file is not the Puppet intermediate CA certificate, Puppet loads the CA key and a mismatched CA certificate, then fails with a misleading error message. The logic for verifying that the key and certificate match is shared with the agent key/certificate verification, and it reports that the *agent* key and certificate are mismatched when the *CA* key and certificate are mismatched.

#### Solution

Copy the CA certificate bundle from the `cacert` file to the `localcacert` file.

```
cp $(puppet master --configprint cacert) $(puppet agent --configprint localcacert)
```