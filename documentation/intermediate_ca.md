---
layout: default
title: "Puppet Server: Intermediate CA"
canonical: "/puppetserver/latest/intermediate_ca.html"
---

Puppet Server supports both a simple CA architecture, with a self-signed root cert that is also used as the CA signing cert; and an intermediate CA architecture, with a self-signed root that issues an intermediate CA cert used for signing incoming certificate requests. The intermediate CA architecture is preferred, because it is more secure and makes regenerating certs easier. To generate a default intermediate CA for Puppet Server, run the `puppetserver ca setup` command before starting your server for the first time.

If you have an external certificate authority, you can create a cert chain from it, and use the `puppetserver ca import` subcommand to install the chain on your server. Puppet agents starting with Puppet 6 handle an intermediate CA setup out of the box. No need to copy files around by hand or configure CRL checking. Like `setup`, `import` needs to be run before starting your server for the first time.

**Note:** The PE installer uses the `puppetserver ca setup` command to create a root cert and an intermediate signing cert for Puppet Server. This means that in PE, the default CA is always an intermediate CA as of PE 2019.0.

**Note:** If for some reason you cannot use an intermediate CA, in Puppet Server 6 starting the server will generate a non-intermediate CA the same as it always did before the introduction of these commands. However, we don't recommend this, as using an intermediate CA provides more security and easier paths for CA regeneration. It is also the default in PE, and some recommended workflows may rely on it.

### Where to set CA configuration

All CA configuration takes place in Puppet’s config file. See the [Puppet Configuration Reference](/puppet/latest/configuration.html) for details.

## Setting up an intermediate CA with an external root

If you want to set up an intermediate CA with an external root cert, you need to supply the following:
- a certificate bundle file consisting of your root cert plus a CA signing cert signed by that root
- a CRL file containing the root’s CRL and the CRL for the new CA cert
- the private key for the intermediate signing cert

Then use `puppetserver ca import` to drop these files in place and trigger the rest of the CA setup.

```
puppetserver ca import --cert-bundle bundle.pem --crl-chain crls.pem --private-key intermediate_key.pem
```

You can always delete your CA per the instructions for regenerating the CA if you decide to switch to an intermediate CA setup later. See [Regenerating all certificates in a Puppet deployment](/puppet/latest/ssl_regenerate_certificates.html) All the caveats around certificate regeneration still apply.

**Note:** Puppet 5 agents still do not support intermediate CAs. If you must use a Puppet 5 agent with a new (or regenerated) Puppet 6 CA, follow the [instructions](/puppetserver/5.3/intermediate_ca_configuration.html) for setting up Puppet 5 agents for intermediate CAs.
