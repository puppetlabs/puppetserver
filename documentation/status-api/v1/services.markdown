---
layout: default
title: "Puppet Server: Status API: Services"
canonical: "/puppetserver/latest/status-api/v1/services.html"
---

[`auth.conf`]: ../../config_file_auth.markdown

The `status` endpoint provides information about the services that Puppet
Server is running.  As of the 2.6.0 release, the only useful information that
the endpoint provides is related to memory usage -- basically the same as that
which the Java MemoryMXBean provides -- and some basic data on process start
and uptime.  See the
[Java MemoryMXBean documentation](https://docs.oracle.com/javase/7/docs/api/java/lang/management/MemoryMXBean.html)
for more information on how to interpret the memory
information.

This is currently an experimental feature provided for troubleshooting
purposes.  The response payload for the `status` endpoint may change without
prior warning in future release.

## `GET /status/v1/services`

(Introduced in Puppet Server 2.6.0)

### Supported HTTP Methods

GET

### Supported Formats

JSON

### Query Parameters

* `level` (optional): One of the values listed below. Status information for all
   registered services will be provided at the requested level of detail.  If
   not provided the default level is "info".
  * `critical`: Returns only the bare minimum amount of status information for
    each service.  Intended to return very quickly and to be suitable for use
    cases like health checks for a load balancer.
  * `info`: Typically returns a bit more info than the "critical" level would,
    for each service.  The specific data that is returned will depend on the
    implementation details of the services loaded in the application, but should
    generally be data that is useful for a human to get a quick impression of the
    health / status of each service.
  * `debug`: This level can be used to request very detailed status information
    about a service, typically used by a human for debugging.  Requesting this
    level of status information may be significantly more expensive than the lower
    levels, depending on the service.  A common use case would be for a service to
    provide some detailed aggregate metrics about the performance or resource
    usage of its subsystems.

  The information returned for any service at each increasing level of detail
  should be additive; in other words, "info" should return the same data
  structure as "critical", but may add additional data in the status field.
  Likewise, "debug" should return the same data structure as "info", but may add
  additional information in the status field.

### Response

The response includes information for services that the status service knows
about.  The service `state` is set as follows:

* `running`: if and only if all services are running.
* `error`: if any service reports error.
* `starting`: if any service reports starting and no service reports error or
   stopping.
* `stopping`: if any service reports stopping and no service reports error.
* `unknown`: if any service reports unknown and no services report error.

A request made to this endpoint will return one of the following status codes:
 
* 200: returned when all services are in `running` state.
- 404: returned when a requested service is not found.
- 503: returned when the service state is `unknown`, `error`, `starting`, or
  `stopping`.

### Example response for GET request with `level=debug`

~~~
GET /status/v1/services?level=debug

HTTP/1.1 200 OK
Content-Type: application/json

{
  "status-service": {
    "detail_level": "debug",
      "service_status_version": 1,
      "service_version": "0.3.5",
      "state": "running",
      "status": {
        "experimental": {
          "jvm-metrics": {
            "heap-memory": {
              "committed": 1049100288,
              "init": 268435456,
              "max": 1908932608,
              "used": 216512656
            },
            "non-heap-memory": {
              "committed": 256466944,
              "init": 2555904,
              "max": -1,
              "used": 173201432
            },
            "start-time-ms": 1472496731281,
            "up-time-ms": 538974
          }
        }
      }
    }
}
~~~

### Authorization

Requests made to the status endpoint are not authorized by either the
Trapperkeeper-based [`auth.conf`] feature or the older Ruby-based authorization
process.  For more information about the Puppet Server authorization process and
configuration settings, see the [`auth.conf` documentation][`auth.conf`].

Unless the `client-auth` setting is set to `required` for the
webserver, a client should be permitted to make a request to this endpoint via
SSL with or without the use of a client certificate.  See [Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md#client-auth)
for more information on the `client-auth` setting.
