---
layout: default
title: "Puppet Server: Status API: Services"
canonical: "/puppetserver/latest/status-api/v1/services.html"
---

[`auth.conf`]: ../../config_file_auth.markdown

The `services` endpoint of Puppet Server's Status API provides information about
services running on Puppet Server. As of Puppet Server 2.6.0, the endpoint provides
information about memory usage similar to the data produced by the Java MemoryMXBean,
as well as basic data on the `pupppetserver` process's state and uptime. See the
[Java MemoryMXBean documentation](https://docs.oracle.com/javase/7/docs/api/java/lang/management/MemoryMXBean.html)
for help interpreting the memory information.

> **Note:** This is an experimental feature provided for troubleshooting
purposes. In future releases, the `services` endpoint's response payload might
change without warning.
>
> For information about HTTP client metrics, which are served from the status endpoint,
> see [their documentation](../../http_client_metrics.markdown).

## `GET /status/v1/services`

(Introduced in Puppet Server 2.6.0)

### Supported HTTP methods

GET

### Supported formats

JSON

### Query parameters

-   `level` (optional): The response includes status information for all
    registered services at the requested level of detail. Default: `info`. Valid
    values:

    -   `critical`: Returns the minimum amount of status information for each
        service. This level returns data quickly and is suitable for frequently
        updating uses, such as health checks for a load balancer.

    -   `info`: Returns more info than the `critical` level for each service.
        The specific data depends on the implementation details of the services
        loaded in the application, but generally includes enough human-readable
        data to provide a quick impression of each service's health and status.

    -   `debug`: This level returns status information about a service in enough
        detail to be suitable for debugging issues with the `puppetserver`
        process. Depending on the service, this level can be significantly more
        expensive than lower levels, reduce the process's performance, and
        generate large amounts of data. This level is suitable for producing
        aggregate metrics about the performance or resource usage of Puppet
        Server's subsystems.

    The information returned for any service at each increasing level of detail
    includes the data from lower levels. In other words, the `info` level returns
    the same data structure as the `critical` level, and might provide additional
    data in the `status` field depending on the service. Likewise, the `debug`
    level returns the same data structure as `info`, and might also add additional
    information in the `status` field.

### Response

The `services` endpoint's response includes information for each service about
which the Status service is aware. Each service's `state` value is one of the
following:

-   `running`, if and only if all services are running
-   `error` if any service reports an error
-   `starting` if any service reports that it is starting, and no service reports
     an error or that it is stopping
-   `stopping` if any service reports that it is stopping and no service reports
     an error
-   `unknown` if any service reports an unknown state and no services report an
     error

Requests to this endpoint return one of the following status codes:

-   200 when all services are in `running` state.
-   404 when a requested service is not found.
-   503 when the service state is `unknown`, `error`, `starting`, or `stopping`

### Example request and response for a debug-level GET request

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

Requests to the `services` endpoint are authorized by the
[Trapperkeeper-based authorization process][`auth.conf`] as of Puppet
Server 5.3.0. For more information about the supported Puppet Server
authorization processes and configuration settings, see the
[`auth.conf` documentation][`auth.conf`].

One may also restrict access to the status service by changing the
`client-auth` setting to `required` for the webserver. See
[Configuring the Webserver Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/blob/master/doc/jetty-config.md#client-auth)
for more information on the `client-auth` setting.
