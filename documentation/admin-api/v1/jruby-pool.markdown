---
layout: default
title: "Puppet Server: Admin API: JRuby Pool"
canonical: "/puppetserver/latest/admin-api/v1/jruby-pool.html"
---

Puppet Server contains a pool of JRuby instances.  Puppet Server adds a new, experimental
endpoint to the master's HTTP API:


## `DELETE /puppet-admin-api/v1/jruby-pool`

This will remove all of the existing JRuby interpreters from the pool, allowing the memory occupied
by these interpreters to be reclaimed by the JVM's garbage collector. The pool will then be refilled
with new JRuby instances, each of which will load the latest Ruby code and related resources from disk.

If you're developing new Ruby plugins that run on the Puppet master (functions, resource types, report handlers),
you may need to force Puppet to re-load its plugins when a new version is ready to test. Killing the JRuby instances
will do this, and it's faster than restarting the entire JVM process.

Furthermore, if you are using multiple environments, this could be useful if you want to make
sure that your JRuby instances are cleaned up and don't have conflicts based
on common code that appears in multiple environments.

This is an experimental feature, and as such the performance impact is unknown at this time. Also, please
note that this operation is computationally expensive, and as such Puppet Server will be unable to fulfill
any incoming requests until the first of the new interpreters has been initialized, which may take several
seconds.


### Response

A successful request to this endpoint will return an `HTTP 204: No Content`.
The response body will be empty.


### Example

~~~
$ curl -i --cert <PATH TO CERT> --key <PATH TO KEY> --cacert <PATH TO PUPPET CA CERT> -X DELETE https://localhost:8140/puppet-admin-api/v1/jruby-pool
HTTP/1.1 204 No Content
~~~


## `GET /puppet-admin-api/v1/jruby-pool/thread-dump`

Retrieve a Ruby thread dump for each JRuby instance registered to the pool.
The thread dump provides a backtrace through the Ruby code that each instance
is executing and is useful for diagnosing instances that have stalled or
are otherwise unresponsive. Backtraces are generated using the JRuby JMX
interface and require the `jruby.management.enabled` property to be set
to `true` in the JVM running Puppet Server.

### Response

A successful request to this endpoint will return a `HTTP 200: Ok` status
code. The response body will be a JSON document containing a map that
associates each JRuby instance ID with a map containing a `thread-dump`
entry that has a string value with the Ruby backtrace.

A `HTTP 500: Internal Server Error` status code will be returned if an
exception occurs while retrieving the thread dump for a JRuby instance,
or if the `jruby.management.enabled` property is not set to `true`.
The response body in this case is also JSON, but the failed instances
will be associated with a map containing a `error` entry with a value
describing the issue.

### Example

~~~
$ curl -si --cert <PATH TO CERT> --key <PATH TO KEY> --cacert <PATH TO PUPPET CA CERT> -X GET https://localhost:8140/puppet-admin-api/v1/jruby-pool/thread-dump
HTTP/1.1 200 OK

{"1":{"thread-dump":"All threads known to Ruby instance 1960016402\n\n ..."}}

# Error returned when jruby.management.enabled is not configured
$ curl -si --cert <PATH TO CERT> --key <PATH TO KEY> --cacert <PATH TO PUPPET CA CERT> -X GET https://localhost:8140/puppet-admin-api/v1/jruby-pool/thread-dump
HTTP/1.1 500 Server Error

{"1":{"error":"JRuby management interface not enabled. Add '-Djruby.management.enabled=true' to JAVA_ARGS to enable thread dumps."}}
~~~

## Relevant Configuration

Access to this endpoint is controlled by the `puppet-admin` section of `puppetserver.conf`. See
[the configuration page](../../configuration.markdown)
for more information.

In the example above, the `curl` command is using a certificate and private key. You must make sure this certificate's name is included in the `puppet-admin -> client-whitelist` setting before you can use it.
