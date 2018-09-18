---
layout: default
title: "Puppet Server: Intermediate CA"
canonical: "/puppetserver/latest/intermediate_ca.html"
---

Puppet Server has a workflow and API for certificate issuance. By default, the server generates a root and intermediate signing CA cert, rather than signing everything off the root. If you have an external certificate authority, you can generate an intermediate signing CA from it, using the `puppetserver ca` subcommand. This way, Puppet agents handle an intermediate CA setup out of the box. No need to copy files around by hand or configure CRL checking. The new CA CLI tool provides two commands to aid in setting up an intermediate CA: `import` and `setup`, as well as other commands for managing an intermediate CA. 

## Available actions

CA subcommand usage: `puppetserver ca <action> [options]`

The available actions:

- `clean`: clean files from the CA for certificates
- `generate`: create a new certificate signed by the CA
- `setup`: generate a root and intermediate signing CA for Puppet Server
- `import`: import the CA's key, certs, and CRLs
- `list`: list all certificate requests
    
    ```
    output format:
    Requested Certificates:
	foo   (SHA256)  24:6E:79:74:7A:D8:21:AA:2C:20:57:5A:9E:CF:3A:12:94:B5:46:C0:E5:2C:5A:1F:E3:A4:0B:3F:1F:53:D5:99
Signed Certificates:
	localhost   (SHA256)  10:A5:50:46:D5:BA:1D:73:25:CF:D4:58:F5:DE:95:34:0F:80:47:08:C0:4F:D4:B9:11:E5:A3:4E:1E:26:43:27    alt names: ["DNS:puppet", "DNS:localhost"]
Revoked Certificates:
	bar   (SHA256)  05:A0:A7:0D:05:D0:BF:FA:07:78:B9:26:6C:F1:75:CF:0E:EF:8A:00:38:BA:64:07:67:29:FA:F9:E5:93:C4:F0
	```

- `revoke`: revoke a given certificate
- `sign`: sign a given certificate

### Where to set CA configuration

All CA configuration takes place in Puppet’s config file. 

## Setting up an intermediate CA 

If you want to set up an intermediate CA with an external root cert, you supply a certificate bundle consisting of the following:
- your root cert plus a CA signing cert signed by that root
- a CRL file containing the root’s CRL and the CRL for the new CA cert
- the private key or the intermediate signing cert

Then use `puppetserver ca import` to drop these files in place and trigger the rest of the CA setup.

If you are working with Puppet in an open source environment, you can also call `setup` to create a default intermediate CA for your install. 

Both `setup` and `import` must be run before starting Puppet Server for the first time and  will not overwrite an existing CA. 

**Note:** The PE installer uses the `puppetserver ca generate` command to create a root cert and an intermediate signing cert for Puppet Server. This means that in PE, the default CA is always an intermediate CA as of PE 2019.0. 


You can always delete your CA per the instructions for regenerating the CA if you decide to switch to an intermediate CA setup later. See [Regenerating all certificates in a Puppet deployment](/puppet/latest/ssl_regenerate_certificates.html) All the caveats around certificate regeneration still apply.


## About the CA commands

Because these commands utilize Puppet Server’s API, all except `setup` and `import` need the server to be running in order to work.

Since these commands are shipped as a gem alongside Puppet Server, it can be updated out-of-band to pick up improvements and bug fixes. To upgrade it, run this command: `/opt/puppetlabs/puppet/bin/gem install -i /opt/puppetlabs/puppet/lib/ruby/vendor_gems puppetserver-ca`

**Note: These commands are available in Puppet 5, but in order to use them, you must update Puppet Server’s `auth.conf` to include a rule allowing the master’s certname to access the `certificate_status` and `certificate_statuses` endpoints. The same applies to upgrading in open source Puppet: if you're upgrading from Puppet 5 to Puppet 6 and are not regenerating your CA, you must whitelist the master’s certname. See [Puppet Server Configuration Files: auth.conf](/puppetserver/latest/config_file_auth.html) for details on how to use `auth.conf`. 


### Signing certs with SANs or auth extensions

With the removal of `puppet cert sign`, it's possible for Puppet Server’s CA API to sign certificates with subject alternative names or auth extensions, which was previously completely disallowed. This is disabled by default for security reasons, but you can turn it on by setting `allow-subject-alt-names` or `allow-authorization-extensions` to true in the `certificate-authority` section of Puppet Server’s config (usually located in `ca.conf`). Once these have been configured, you can use `puppetserver ca sign --certname <name>` to sign certificates with these additions.

### Infrastructure certificate revocation list

The infrastructure certificate revocation list is disabled by default in open source Puppet, and enabled in PE. To toggle it, update `enable-infra-crl` in the `certificate-authority` section of `puppetserver.conf`.  

The Puppet Server CA can create a smaller CRL that contains only revocations of those nodes that agents are expected to talk to during normal operations, for example, compile masters or hosts that agents connect to as part of agent-side functions. Puppet Server CA can distribute that CRL to agents, rather than the CRL it maintains with all node revocations.

To create a smaller CRL, manage the content of the file at `$cadir/infra_inventory.txt`. Provide a newline-separated list of the certnames. When revoked, they are added to the Infra CRL. The certnames must match existing certificates issued and maintained by the Puppet Server CA. Setting the value `certificate-authority.enable-infra-crl` to `true` causes Puppet Server to update both its Full CRL and its Infra CRL with the certs that match those certnames when revoked. When agents first check in, they receive a CRL that includes only the revocations of certnames listed in the `infra_inventory.txt`.

This feature is disabled by default because the definition of what constitutes an "infrastructure" node is site-specific and sites with a standard, single master configuration have no need for the additional work. After having enabled the feature, if you want to go back, remove the explicit setting and reload Puppet Server to turn the default off; then, when agents first check, they receive the Full CRL as before (including any infrastructure nodes that were revoked while the feature was enabled).
