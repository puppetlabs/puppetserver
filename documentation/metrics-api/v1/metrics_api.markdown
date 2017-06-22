---
layout: default
title: "Puppet Server: Metrics API v1"
canonical: "/puppetserver/latest/metrics-api/v1/metrics_api.html"
---

By default, Puppet Server enables two optional web APIs for
[Java Management Extension (JMX)](https://docs.oracle.com/javase/tutorial/jmx/index.html)
metrics, namely
[managed beans (MBeans)](https://docs.oracle.com/javase/tutorial/jmx/mbeans/). For the newer Jolokia-based metrics API, see [the `/metrics/v2` documentation](../v2/metrics_api.html).

The metrics v1 API was introduced in Puppet Enterprise 2016.4 and is now open sourced. It is still
enabled but is deprecated.

> **Note:** The metrics described here are returned only when passing the
> `level=debug` URL parameter, and the structure of the returned data might
> change, or the endpoint might be removed, in future versions.

### `GET /metrics/v1/mbeans`

The `GET /metrics/v1/mbeans` endpoint lists available MBeans.

#### Response keys

-   The key is the name of a valid MBean.
-   The value is a URI to use when requesting that MBean's attributes.

### `POST /metrics/v1/mbeans`

The `POST /metrics/v1/mbeans` endpoint retrieves requested MBean metrics.

#### Query parameters

The query doesn't require any parameters, but the request body must contain a
JSON object whose values are metric names, or a JSON array of metric names, or
a JSON string containing a single metric's name.

For a list of metric names, make a `GET` request to `/metrics/v1/mbeans`.

#### Response keys

The response format, though always JSON, depends on the request format:

-   Requests with a JSON object return a JSON object where the values of the
    original object are transformed into the Mbeans' attributes for the metric
    names.
-   Requests with a JSON array return a JSON array where the items of the original
    array are transformed into the Mbeans' attributes for the metric names.
-   Requests with a JSON string return a JSON object of the Mbean's attributes
    for the given metric name.

### GET /metrics/v1/mbeans/<name>

The `GET /metrics/v1/mbeans/<name>` endpoint reports on a single metric.

#### Query parameters

The query doesn't require any parameters, but the endpoint itself must
correspond to one of the metrics returned by a `GET` request to `/metrics/v1/mbeans`.

#### Response keys

The endpoint's responses contain a JSON object mapping strings to values. The
keys and values returned in the response vary based on the specified metric.

#### Example

Use `curl` from localhost to request data on MBean memory usage:

    curl 'http://localhost:8080/metrics/v1/mbeans/java.lang:type=Memory'

The response should contain a JSON object representing the data:

``` json
{
  "ObjectPendingFinalizationCount" : 0,
  "HeapMemoryUsage" : {
    "committed" : 807403520,
    "init" : 268435456,
    "max" : 3817865216,
    "used" : 129257096
  },
  "NonHeapMemoryUsage" : {
    "committed" : 85590016,
    "init" : 24576000,
    "max" : 184549376,
    "used" : 85364904
  },
  "Verbose" : false,
  "ObjectName" : "java.lang:type=Memory"
}
```
