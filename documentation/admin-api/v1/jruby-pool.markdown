
Puppet Server contains a pool of JRuby Instances.  Puppet Server adds a new
endpoint to the master's HTTP API:


## `DELETE /puppet-admin-api/v1/jruby-pool`

To flush the JRuby pool, make an HTTP
request to this endpoint.


### Response

A successful request to this endpoint will return an `HTTP 204: No Content`.
The response body will be empty.


### Example
```
$ curl -i -k -X DELETE https://localhost:8140/puppet-admin-api/v1/jruby-pool
HTTP/1.1 204 No Content
```


### Relevant Configuration

This endpoint is gated behind the security provisions in the `puppet-admin`
part of the configuration data; see
[this page](https://github.com/puppetlabs/puppet-server/blob/master/documentation/configuration.markdown)
for more information.  In the example above, we have configured
`authorization-required: false` for brevity.
