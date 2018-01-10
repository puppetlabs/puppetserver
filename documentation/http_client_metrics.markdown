---
layout: default
title: "Puppet Server: HTTP Client Metrics"
canonical: "/puppetserver/latest/http_client_metrics.html"
---

[status API]: ./status-api/v1/services.markdown

HTTP client metrics available in Puppet Server 5 allows users to measure how long it takes for Puppet Server to make requests to and receive responses from other services, such as PuppetDB.

## Determining metrics IDs

All of these metrics are of the form `puppetlabs.<SERVER ID>.http-client.experimental.with-metric-id.<METRIC ID>.full-response`.

> **Note:** The `<METRIC ID>` describes what the metric measures. A metric ID is represented in the [status endpoint](./status-api/v1/services.markdown) as an array of strings, and in the metric itself the strings are joined together with periods. For instance, the metric ID of `[puppetdb resource search]` is `puppetdb.resource.search`, so the full metric name would be `puppetlabs.<server-id>.http-client.experimental.with-metric-id.puppetdb.resource.search.full-response`.

You can configure PuppetDB to be a backend for [configuration files](https://puppet.com/docs/puppetdb/latest/connect_puppet_master.html#step-2-edit-configuration-files) (through the `storeconfigs` setting), and you can configure Puppet Server to send reports to an external report processing service. If you configure either of these, then during the course of handling a Puppet agent run, Puppet Server makes several calls to external services to retrieve or store information.

-   During handling of a `/puppet/v3/node` request, Puppet Server issues:
    -   a `facts find` request to PuppetDB for facts about the node, if they aren't yet cached (typically the first time it requests facts for the node). **Metric ID:** `[puppetdb facts find]`.
-   During handling of a `/puppet/v3/catalog` request, Puppet Server issues several requests:
    -   a PuppetDB `replace facts` request, to replace the facts for the agent in PuppetDB with the facts it received from the agent. **Metric ID:** `[puppetdb, command, replace_facts]`.
    -   a PuppetDB `resource search` request, to search for resources if exported resources are used. **Metric ID:** `[puppetdb, resource, search]`.
    -   a PuppetDB `query` request, if the `puppetdb_query` function is used in Puppet code. **Metric ID:** `[puppetdb, query]`.
    -   a PuppetDB `replace catalog` request, to replace the catalog for the agent in PuppetDB with the newly compiled catalog. **Metric ID:** `[puppetdb, command, replace_catalog]`.
-   During handling of a `/puppet/v3/report` request, Puppet Server issues:
    -   a PuppetDB `store report` request, to store the submitted report. **Metric ID:** `[puppetdb command store_report]`.
    -   a request to the configured `reports_url` to store the report, if the HTTP report processor is enabled. **Metric ID:** `[puppetdb report http]`.

## Configuring

HTTP client metrics are enabled by default, but can be disabled by setting `metrics-enabled` to `false` in the `http-client` section of [`puppetserver.conf`](./config_file_puppetserver.markdown).

These metrics also depend on the `server-id` setting in the `metrics` section of `puppetserver.conf`. This defaults to `localhost`, and while `localhost` can collect metrics, change this setting to something unique to avoid metric naming collisions when exporting metrics to an external tool, such as Graphite.

This data is all available via the [status API][] endpoint, at `https://<MASTER HOSTNAME>:8140/status/v1/services/master?level=debug`. Puppet Server 5.0 adds a `http-client-metrics` keyword in the map. If metrics are not enabled, or if Puppet Server has not issued any requests yet, then this array will be empty, like so: `"http-client-metrics": []`.

In the [sample Grafana dashboard](./sample-puppetserver-metrics-dashboard.json), the `External HTTP Communications` graph visualizes all of these metrics, and the tooltip describes each of them.

## Example metrics output

```json
"http-client-metrics": [
  {
    "aggregate": 407,
    "count": 1,
    "mean": 407,
    "metric-id": [
      "puppetdb",
      "facts",
      "find"
    ],
    "metric-name": "puppetlabs.localhost.http-client.experimental.with-metric-id.puppetdb.facts.find.full-response"
  },
  {
    "aggregate": 66,
    "count": 1,
    "mean": 66,
    "metric-id": [
      "puppetdb",
      "command",
      "replace_facts"
    ],
    "metric-name": "puppetlabs.localhost.http-client.experimental.with-metric-id.puppetdb.command.replace_facts.full-response"
  },
  {
    "aggregate": 60,
    "count": 2,
    "mean": 30,
    "metric-id": [
      "puppetdb",
      "resource",
      "search"
    ],
    "metric-name": "puppetlabs.localhost.http-client.experimental.with-metric-id.puppetdb.resource.search.full-response"
  },
  {
    "aggregate": 53,
    "count": 1,
    "mean": 53,
    "metric-id": [
      "puppetdb",
      "query"
    ],
    "metric-name": "puppetlabs.localhost.http-client.experimental.with-metric-id.puppetdb.query.full-response"
  },
  {
    "aggregate": 22,
    "count": 1,
    "mean": 22,
    "metric-id": [
      "puppetdb",
      "command",
      "store_report"
    ],
    "metric-name": "puppetlabs.localhost.http-client.experimental.with-metric-id.puppetdb.command.store_report.full-response"
  },
  {
    "aggregate": 16,
    "count": 1,
    "mean": 16,
    "metric-id": [
      "puppetdb",
      "command",
      "replace_catalog"
    ],
    "metric-name": "puppetlabs.localhost.http-client.experimental.with-metric-id.puppetdb.command.replace_catalog.full-response"
  },
  {
    "aggregate": 2,
    "count": 1,
    "mean": 2,
    "metric-id": [
      "puppet",
      "report",
      "http"
    ],
    "metric-name": "puppetlabs.localhost.http-client.experimental.with-metric-id.puppet.report.http.full-response"
  }
],
```

