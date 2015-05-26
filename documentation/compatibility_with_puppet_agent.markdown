# Puppet 3 Backwards Compatibility

Puppet Server 2.1 introduces a new feature that allows Puppet 3 agents to
operate with puppetserver.  Puppet Server 1.x requires Puppet 3 while Puppet
Server 2.x requires Puppet 4.  These requirements created a situation where
only Puppet 4 agents operate with Puppet Server 2.0 masters.  We've added
Puppet 3 support in Puppet Server 2.1 to ease the upgrade process for users.

Compatibility is provided by a so-called `legacy-routes-service`.  This service
intercepts all Puppet 3 REST API requests and transforms the URL structure and
request headers into a Puppet 4 compatible request which is handled normally by
the Puppet Master service.  The biggest implication of this implementation is
that Puppet's `auth.conf` rules need special attention if they've been
customized from their default values because all Puppet 3 requests have already
been transformed into Puppet 4 requests by the time `auth.conf` rules are
enforced.

Other implications of this implementation are discrepancies in the request and
response REST API headers.   These differences are worth noting, but should
have no effect on typical deployments.

# Configuration settings

When operating Puppet 3 agents against Puppet Server 2.1, some configuration
changes on the agents may be required ahead of time.  Two settings in
particular should be configured:

 * `stringify_facts = false` which is required with a Puppet 4 master.
 * The master will operate with the future parser turned on, so if the future
   parser is not being used with Puppet 3, please turn it on and try it out
   before upgrading to Puppet Server 2.1.  Any incompatibilities between
   existing Puppet code and the future parser should be resolved prior to
   upgrading to Puppet Server 2.1.

# auth.conf

The REST API URL structure has changed starting in Puppet 4.  The major change
is the introduction of a versioned API rooted at `/puppet`, with the CA API
separated out at `/puppet-ca`.  In the general case a Puppet 3 URL can be
translated to Puppet 4 by stripping off the environment name prefix, pre-pending
`/puppet/v3`, and then adding back the environment name as a query parameter.
If `auth.conf` has been customized and Puppet Server 2.1 is going to be used
with Puppet 3 agents then the Puppet 3 requests need to be translated in this
way and expressed in auth.conf in their Puppet 4 form.

For example, consider a customized deployment that uses UUID's to uniquely
identify certificates, but omits the UUID from the node-name to facilitate
de-provisioning and re-provisioning the same node identity using unique
certificates.

    # puppet.conf on the agent
    [main]
    certname = emanon.uuid.ec7f5196-7f63-5f73-f18d-ca69afc5c24d
    node_name_value = emanon.uuid

To support this configuration in Puppet 3 auth.conf has been customized as
follows:

    # Puppet 3 auth.conf on the master
    path ~ ^/catalog/([^/]+).uuid$
    method find
    allow /^$1\.uuid.*/
    # Default rule, should follow the more specific rules
    path ~ ^/catalog/([^/]+)$
    method find
    allow $1

This configuration will not work in Puppet Server 2 with Puppet 3 because of
the translation to Puppet 4 format URL's.  A working configuration looks like
so:

    # Puppet 3 & 4 compatible auth.conf with Puppet Server 2.1
    path ~ ^/puppet/v3/catalog/([^/]+).uuid$
    method find
    allow /^$1\.uuid.*/
    # Default rule, should follow the more specific rules
    path ~ ^/puppet/v3/catalog/([^/]+)$
    method find
    allow $1

Note the `/puppet/v3` prefix of the path regular expression.  For more
information about Puppet 3 and Puppet 4 API request and their differences
please see the [HTTP API](https://docs.puppetlabs.com/guides/rest_api.html)
documentation.  The default Puppet 4.1.0 auth.conf is located
[here](https://github.com/puppetlabs/puppet/blob/4.1.0/conf/auth.conf) and the
default Puppet 3.8.0 auth.conf is located
[here](https://github.com/puppetlabs/puppet/blob/3.8.0/conf/auth.conf) for
comparison.

# HTTP Headers

When Puppet Server 2.1 handles requests from Puppet 3 agents, there are some
differences with respect to headers and "normal" Puppet 4 REST API requests.
Specifically, the X-Puppet-Version header will not be present for Puppet 3
requests which should only affect users relying on this header for advanced
configuration of third party reverse proxies, e.g. HAProxy or NGINX.  In
addition, the Accept: request header is munged in a manner suitable for use
with content the Puppet 4 master service is able to provide.  Accept: raw and
Accept: s are translated to Accept: binary as an example.  The munging of the
Accept header should not affect any Puppet deployment because this header is
rarely, if ever, used in advanced reverse proxy configurations with Puppet.
Finally, the API request to obtain file bucket content is modified such that
the content-type is treated as application/octet-stream internally in Puppet
Server, but the request and the response are treated as text/plain for
compatibility with Puppet 3.  This too should have no impact on any Puppet
deployment.
