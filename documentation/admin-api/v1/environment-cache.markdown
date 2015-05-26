---
layout: default
title: "Puppet Server: Admin API: Environment Cache"
canonical: "/puppetserver/latest/admin-api/v1/environment-cache.html"
---

When using directory environments, the Puppet master
[caches](https://docs.puppetlabs.com/puppet/latest/reference/environments_configuring.html)
the data it loads from disk for each environment.  Puppet Server adds a new
endpoint to the master's HTTP API:


## `DELETE /puppet-admin-api/v1/environment-cache`

To trigger a complete invalidation of the data in this cache, make an HTTP
request to this endpoint.

### Query Parameters

(Introduced in Puppet Server 1.1/2.1)

This endpoint accepts an optional query parameter, `environment`, whose value
may be set to the name of a specific Puppet environment.  If this parameter
is provided, only the specified environment will be flushed from the cache,
as opposed to all environments.

### Response

A successful request to this endpoint will return an `HTTP 204: No Content`.
The response body will be empty.


### Example

~~~
$ curl -i --cert <PATH TO CERT> --key <PATH TO KEY> --cacert <PATH TO PUPPET CA CERT> -X DELETE https://localhost:8140/puppet-admin-api/v1/environment-cache
HTTP/1.1 204 No Content

$ curl -i --cert <PATH TO CERT> --key <PATH TO KEY> --cacert <PATH TO PUPPET CA CERT> -X DELETE https://localhost:8140/puppet-admin-api/v1/environment-cache?environment=production
HTTP/1.1 204 No Content
~~~

## Relevant Configuration

Access to this endpoint is controlled by the `puppet-admin` section of `puppetserver.conf`. See
[the configuration page](../../configuration.markdown)
for more information.

In the example above, the `curl` command is using a certificate and private key. You must make sure this certificate's name is included in the `puppet-admin -> client-whitelist` setting before you can use it.
