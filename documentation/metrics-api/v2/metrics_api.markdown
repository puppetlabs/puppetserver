---
layout: default
title: "Puppet Server: Metrics API"
canonical: "/puppetserver/latest/metrics_api.html"
---

Puppet Server includes two optional, enabled-by-default web APIs for
[Java Management Extension (JMX)](https://docs.oracle.com/javase/tutorial/jmx/index.html)
metrics, namely
[managed beans (MBeans)](https://docs.oracle.com/javase/tutorial/jmx/mbeans/).

## Metrics v2

The first uses the [Jolokia](https://jolokia.org) library and is accessible at:

    /metrics/v2/

Since the v2 API is implimented using a large open source metrics library with
its own extensive documentation, below we will only walk through a very brief
overview with the most common configuration and queries. Each section includes
links to Jolokia's comprehensive documentation.

### Configuration

For security reasons only the read operations of the Jolokia interface are
enabled by default. This includes:

* `read`
* `list`
* `version`
* `search`

Users may change the security access policy by providing their own
`/etc/puppetlabs/puppetserver/jolokia-access.xml` file that follows the
[Jolokia access policy](https://jolokia.org/reference/html/security.html)

Further configuration, for optimizations or troubleshooting, is possible by
editing the `metrics.metrics-webservice.jolokia.servlet-init-params` table
within the `/etc/puppetlabs/puppetserver/conf.d/metrics.conf` file. See
Jolokia's [agent initialization documentation](https://jolokia.org/reference/html/agents.html#agent-war-init-params)
for all of the available options. The v2 endpoint may be disabled completely
by setting the `metrics.metrics-webservice.jolokia.enabled` field to `false`.

### Usage

Queries against the metrics v2 api take the general form

    GET /metrics/v2/<operation>/<query>

or

    POST /metrics/v2/<operation>

with the query as a JSON document in the body of the POST.

To list all valid mbeans querying the metrics endpoint

    GET /metrics/v2/list

Which should return a response similar to

``` json
{
  "request": {
    "type": "list"
  },
  "value": {
    "java.util.logging": {
      "type=Logging": {
        "op": {
          "getLoggerLevel": {
            ...
          },
          ...
        },
        "attr": {
          "LoggerNames": {
            "rw": false,
            "type": "[Ljava.lang.String;",
            "desc": "LoggerNames"
          },
          "ObjectName": {
            "rw": false,
            "type": "javax.management.ObjectName",
            "desc": "ObjectName"
          }
        },
        "desc": "Information on the management interface of the MBean"
      }
    },
    ...
  }
}
```

The MBean names can then be created by joining the the first two keys of the
value table with a colon (the `domain` and `prop list` in Jolokia parlance).
Querying the MBeans is achieved via the `read` operation. The `read` operation
has as its GET signature

    GET /metrics/v2/read/<mbean names>/<attributes>/<optional inner path filter>

So, from the example above we could query for the registered logger names with
this HTTP call:

    GET /metrics/v2/read/java.util.logging:type=Logging/LoggerNames

Which would return the JSON document

``` json
{
  "request": {
    "mbean": "java.util.logging:type=Logging",
    "attribute": "LoggerNames",
    "type": "read"
  },
  "value": [
    "javax.management.snmp",
    "global",
    "javax.management.notification",
    "javax.management.modelmbean",
    "javax.management.timer",
    "javax.management",
    "javax.management.mlet",
    "javax.management.mbeanserver",
    "javax.management.snmp.daemon",
    "javax.management.relation",
    "javax.management.monitor",
    "javax.management.misc",
    ""
  ],
  "timestamp": 1497977258,
  "status": 200
}
```

Two advanced features that the new Jolokia based metrics api provides are
globbing and response filtering. An example of using both of these features
to query garbage collection data, but only return collection counts and times
is to make this api request

    GET metrics/v2/read/java.lang:name=*,type=GarbageCollector/CollectionCount,CollectionTime

Which returns a response like:

``` json
{
  "request": {
    "mbean": "java.lang:name=*,type=GarbageCollector",
    "attribute": [
      "CollectionCount",
      "CollectionTime"
    ],
    "type": "read"
  },
  "value": {
    "java.lang:name=PS Scavenge,type=GarbageCollector": {
      "CollectionTime": 1314,
      "CollectionCount": 27
    },
    "java.lang:name=PS MarkSweep,type=GarbageCollector": {
      "CollectionTime": 580,
      "CollectionCount": 5
    }
  },
  "timestamp": 1497977710,
  "status": 200
}
```

Please refer to the
[Jolokia protocol documentation](https://jolokia.org/reference/html/protocol.html)
for more advanced usage.

## Metrics v1

The previous v1 API was provided in PE and is now open sourced. It is still
enabled but is deprecated. The v1 API includes the endpoints

* `GET /metrics/v1/mbeans`
* `POST /metrics/v1/mbeans`
* `GET /metrics/v1/mbeans/<name>`

> **Note:** The metrics described here are returned only when passing the
> `level=debug` URL parameter, and the structure of the returned data might
> change in future versions.
>

### GET /metrics/v1/mbeans

The `GET /metrics/v1/mbeans` endpoint lists available MBeans.

#### Response keys

* The key is the name of a valid MBean.
* The value is a URI to use when requesting that MBean's attributes.

### POST /metrics/v1/mbeans

The `POST /metrics/v1/mbeans` endpoint retrieves requested MBean metrics.

#### Query parameters

The query doesn't require any parameters, but the request body must contain a
JSON object whose values are metric names, or a JSON array of metric names, or
a JSON string containing a single metric's name.

For a list of metric names, make a `GET` request to `/metrics/v1/mbeans`.

#### Response keys

The response format, though always JSON, depends on the request format:

* Requests with a JSON object return a JSON object where the values of the
  original object are transformed into the Mbeans' attributes for the metric
  names.
* Requests with a JSON array return a JSON array where the items of the original
  array are transformed into the Mbeans' attributes for the metric names.
* Requests with a JSON string return the a JSON object of the Mbean's attributes
  for the given metric name.

### GET /metrics/v1/mbeans/<name>

The `GET /metrics/v1/mbeans/<name>` endpoint reports on a single metric.

#### Query parameters

The query doesn't require any parameters, but the endpoint itself must
correspond to one of the metrics returned by a `GET` request to `/metrics/v1/mbeans`.

#### Response keys

The endpoint's responses contain a JSON object mapping strings to values. The
keys and values returned in the response vary based on the specified metric.

For example:

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