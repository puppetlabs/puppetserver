---
layout: default
title: "Puppet Server: Backwards Compatibility With Puppet 3 Agents"
canonical: "/puppetserver/latest/compatibility_with_puppet_agent.markdown"
---


[ca.conf]: ./configuration.html#caconf
[auth.conf]: /puppet/latest/reference/config_file_auth.html

This version of Puppet Server can serve configurations to both Puppet 4 and Puppet 3 agent nodes. This feature was added in Puppet Server 2.1.

Once you get your Puppet 3 nodes talking to a newer Puppet Server, you should start upgrading them to Puppet 4.


## Preparing Puppet 3 Nodes for Puppet Server 2

Backwards compatibility is enabled by default, but you should make sure your Puppet code is ready for Puppet 4 before pointing old agent nodes at a new Puppet server. You may also need to edit auth.conf.

Before migrating Puppet 3 nodes to Puppet Server 2, do all of the following:

- Update Puppet to the most recent 3.x version on your agent nodes and existing Puppet master.
- Set `stringify_facts = false` on all nodes, and fix your code if necessary.
- Set `parser = future` on your Puppet master(s), and fix your code if necessary.
- Modify your custom auth.conf rules for the new Puppet Server version.

Keep reading for more details.

### Update Puppet 3.x

Make sure your existing 3.x deployment is running the most recent Puppet 3 version. This is especially important on your Puppet master, where you'll want the newest version of the future parser.

### Stop Stringifying Facts

Set `stringify_facts = false` in puppet.conf on every node in your deployment. This will match Puppet 4's behavior. If you want to edit puppet.conf using Puppet, you can use an [`inifile` resource](https://forge.puppetlabs.com/puppetlabs/inifile).

**Note:** If any of your Puppet code _treats boolean facts like strings,_ this will break something. Search for comparisons like `if $::is_virtual == "true" {...}`. If you need to support 4.x and 3.x with the same code, you can use something like `if str2bool("$::is_virtual") {...}`.

### Use the Future Parser

Set `parser = future` in puppet.conf on your Puppet master servers. This lets them compile catalogs using Puppet 4's version of the Puppet language.

Run Puppet with the future parser enabled for a while before trying to upgrade, and resolve any language incompatibilities first.

### Transfer and Modify Custom auth.conf Rules

Puppet 3 and 4 use different HTTPS URLs to fetch configurations. Puppet Server lets agents make requests at the old URLs, but internally it handles them as requests to the new endpoints. Any rules in auth.conf that match Puppet 3-style URLs will have _no effect._

This means you must:

* Find any _custom_ rules you've added to your [auth.conf file][auth.conf]. (Don't worry about default rules.)
* Change each `path` to match Puppet 4 URLs.
    * Add `/puppet/v3` to the beginning of most paths.
    * The `certificate_status` endpoint ignores auth.conf; configure access in Puppet Server's [ca.conf][] file.
* Add the rules to `/etc/puppetlabs/puppet/auth.conf` on your Puppet Server.

For more information, see:

* [Puppet's HTTPS API (current)](/puppet/latest/reference/http_api/http_api_index.html)
* [Puppet's HTTPS API (3.x)](https://github.com/puppetlabs/puppet/blob/3.8.0/api/docs/http_api_index.md)
* [Default Puppet 4.1.0 auth.conf](https://github.com/puppetlabs/puppet/blob/4.1.0/conf/auth.conf)
* [Default Puppet 3.8.0 auth.conf](https://github.com/puppetlabs/puppet/blob/3.8.0/conf/auth.conf)

#### auth.conf Rule Example

Puppet 3 rules:

    # Puppet 3 auth.conf on the master
    path ~ ^/catalog/([^/]+).uuid$
    method find
    allow /^$1\.uuid.*/

    # Default rule, should follow the more specific rules
    path ~ ^/catalog/([^/]+)$
    method find
    allow $1

Puppet Server 2 rules supporting both 3.x and 4.x agent nodes:

    # Puppet 3 & 4 compatible auth.conf with Puppet Server 2.1
    path ~ ^/puppet/v3/catalog/([^/]+).uuid$
    method find
    allow /^$1\.uuid.*/

    # Default rule, should follow the more specific rules
    path ~ ^/puppet/v3/catalog/([^/]+)$
    method find
    allow $1

## Details About Backwards Compatibility

Compatibility is provided by the `legacy-routes-service`, which intercepts Puppet 3 HTTPS API requests, transforms the URLs and request headers into Puppet 4 compatible requests, and passes them to the Puppet master service.

### HTTP Headers

There are some minor differences in headers between native Puppet 4 requests and modified Puppet 3 requests. Specifically:

* The `X-Puppet-Version` header is absent in Puppet 3 requests. This should only matter if you use this header to configure a third party reverse proxy like HAProxy or NGINX.
* The `Accept:` request header is munged to match the content the Puppet 4 master service can provide. For example, `Accept: raw` and `Accept: s` are translated to `Accept: binary`.

    This shouldn't affect any Puppet deployment because this header is rarely, if ever, used to configure reverse proxies.
* The `content-type` for file bucket content requests is internally treated as `application/octet-stream`, but the request and the response are treated as `text/plain` for compatibility with Puppet 3.  This too should have no impact on any Puppet deployment.
