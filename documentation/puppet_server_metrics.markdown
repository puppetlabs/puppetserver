---
layout: default
title: "Monitoring Puppet Server metrics"
canonical: "/puppetserver/latest/puppet_server_metrics.html"
---

[metrics API]: ./metrics-api/v1/metrics_api.markdown
[developer dashboard]: #using-the-developer-dashboard
[Graphite]: https://graphiteapp.org
[Grafana]: http://grafana.org
[sample Grafana dashboard]: ./sample-puppetserver-metrics-dashboard.json
[static catalogs]: https://puppet.com/docs/puppet/latest/static_catalogs.html
[HTTP client metrics]: ./http_client_metrics.markdown
[`grafanadash`]: https://forge.puppet.com/cprice404/grafanadash
[`metrics.conf`]: ./config_file_metrics.markdown

Puppet Server tracks several advanced performance and health metrics, all of which take advantage of the [metrics API][]. You can track these metrics using:

-   Customizable, networked [Graphite and Grafana instances](#getting-started-with-graphite)
-   [HTTP client metrics][]
-   [Metrics API][metrics API] endpoints

To visualize Puppet Server metrics, either:

- Export them to a Graphite installation. The [grafanadash](https://forge.puppet.com/puppetlabs/grafanadash) module helps you set up a Graphite instance, configure Puppet Server for exporting to it, and visualize the output with Grafana. You can later integrate this with your Graphite installation. For more information, see Getting started with Graphite below.
- Use the [puppet-metrics-dashboard](https://forge.puppet.com/puppetlabs/puppet_metrics_dashboard) — this does not go through the Graphite exporting feature. The puppet-metrics-dashboard queries the metrics HTTP API directly and saves the results to disk. It also includes a Docker image of Graphite and Grafana for easy visualization. For more information, see [Puppet Metrics Collection](https://github.com/puppetlabs/best-practices/blob/master/puppet-enterprise-metrics-collection.md).

The puppet-metrics-dashboard is the recommended option for FOSS users, as it is an easier way to save and visualize Puppet Server metrics. The `grafanadash` is still useful for users exporting to their existing Graphite installation.

> **Note:** The `grafanadash` and `puppet-graphite` modules referenced in this document are _not_ Puppet-supported modules. They are provided as testing and demonstration purposes _only_. 

## Getting started with Graphite

[Graphite][] is a third-party monitoring application that stores real-time metrics and provides customizable ways to view them. Puppet Server can export many metrics to Graphite, and exports a set of metrics by default that is designed to be immediately useful to Puppet administrators.

> **Note:** A Graphite setup is deeply customizable and can report many Puppet Server metrics on demand. However, it requires considerable configuration and additional server resources. To retrieve metrics through HTTP requests, see the metrics API.

To start using Graphite with Puppet Server, you must:

-   [Install and configure a Graphite server](https://graphite.readthedocs.io/en/latest/install.html).
-   [Enable Puppet Server's Graphite support](#enabling-puppet-servers-graphite-support).

[Grafana][] provides a web-based customizable dashboard that's compatible with Graphite, and the [`grafanadash`][] module installs and configures it by default.

### Using the `grafanadash` module to quickly set up a Graphite demo server

The [`grafanadash`][] Puppet module quickly installs and configures a basic test instance of [Graphite][] with the [Grafana][] extension. When installed on a dedicated Puppet agent, this module provides a quick demonstration of how Graphite and Grafana can consume and display Puppet Server metrics.

> **WARNING:** The `grafanadash` module is _not_ a Puppet-supported module. It is designed for testing and demonstration purposes _only_, and tested against CentOS 6 only.
>
> Also, install this module on a dedicated agent _only_. Do **not** install it on the node running Puppet Server, because the module makes security policy changes that are inappropriate for a Puppet master:
>
> -   SELinux can cause issues with Graphite and Grafana, so the module temporarily disables SELinux. If you reboot the machine after using the module to install Graphite, you must disable SELinux again and restart the Apache service to use Graphite and Grafana.
>
> -   The module disables the `iptables` firewall and enables cross-origin resource sharing on Apache, which are potential security risks.

#### Installing the `grafanadash` Puppet module

Install the `grafanadash` Puppet module on a \*nix agent. The module's `grafanadash::dev` class installs and configures a Graphite server, the Grafana extension, and a default dashboard.

1.  [Install a \*nix Puppet agent](https://puppet.com/docs/puppet/latest/install_linux.html) to serve as the Graphite server.

2.  As root on the Puppet agent node, run `puppet module install puppetlabs-grafanadash`.

3.  As root on the Puppet agent node, run `puppet apply -e 'include grafanadash::dev'`.

#### Running Grafana

Grafana runs as a web dashboard, and the `grafanadash` module configures it to use port 10000 by default. To view Puppet metrics in Grafana, you must create a metrics dashboard, or edit and import a JSON-based dashboard that includes Puppet metrics, such as the [sample Grafana dashboard][] that we provide.

1.  In a web browser on a computer that can reach the Puppet agent node running Grafana, navigate to `http://<AGENT'S HOSTNAME>:10000`.

    There, you'll see a test screen that indicates whether Grafana can successfully connect to your Graphite server.

    If Grafana is configured to use a hostname that the computer on which the browser is running cannot resolve, click **view details** and then the **Requests** tab to determine the hostname Grafana is trying to use. Next, add the IP address and hostname to the computer's `/etc/hosts` file on Linux or OS X, or `C:\Windows\system32\drivers\etc\hosts` file on Windows.

2.  Download and edit our [sample Grafana dashboard][], `sample_metrics_dashboard.json`.

    a.  Open the `sample_metrics_dashboard.json` file in a text editor on the same computer you're using to access Grafana.

    b.  Throughout the file, replace our sample hostname of `master.example.com` with your Puppet Server's hostname. (**Note:** This value **must** be used as the `metrics_server_id` setting, as configured below.)

    c.  Save the file.

3.  In the Grafana UI, click **search** (the folder icon), then **Import**, then **Browse**.

4.  Navigate to and select the edited JSON file.

This loads a dashboard with nine graphs that display various metrics exported from the Puppet Server to the Graphite server. (For details, see [Using the Grafana dashboard](#using-the-sample-grafana-dashboard).) However, these graphs will remain empty until you enable Puppet Server's Graphite metrics.

> Note: If you want to integrate Puppet Server's Grafana exporting with your own infrastructure, use the `grafanadash` module. If you want visualization of metrics, use the `puppetlabs-puppet_metrics_dashboard` module. See [Puppet Metrics Collection](https://github.com/puppetlabs/best-practices/blob/master/puppet-enterprise-metrics-collection.md) for more information. 

### Enabling Puppet Server's Graphite support

Configure Puppet Server's [`metrics.conf`](./config_file_metrics.markdown) file to enable and use the Graphite server.

1.  Set the `enabled` parameter to true in `metrics.registries.puppetserver.reporters.graphite`:

    ```
     metrics: {
        server-id: localhost
        registries: {
            puppetserver: {
                ...
                reporters: {
                    ...
                    # enable or disable Graphite metrics reporter
                    graphite: {
                        enabled: true
                    }
                }
            }
        }
    }
    ```

2.  Configure the Graphite host settings in `metrics.reporters.graphite`:
    -   **host:** The Graphite host's IP address as a string.
    -   **port:** The Graphite host's port number.
    -   **update-interval-seconds:** How frequently Puppet Server should send metrics ot Graphite.

3.  Verify that `metrics.registries.puppetserver.reporters.jmx.enabled` is not set to false. Its default setting is true.

> **Tip:** In the Grafana UI, choose an appropriate time window from the drop-down menu.

### Using the sample Grafana dashboard

The [sample Grafana dashboard][] provides what we think is an interesting starting point. You can click on the title of any graph, and then click **edit** to tweak the graphs as you see fit.

-   **Active requests:** This graph serves as a "health check" for the Puppet Server. It shows a flat line that represents the number of CPUs you have in your system, a metric that indicates the total number of HTTP requests actively being processed by the server at any moment in time, and a rolling average of the number of active requests. If the number of requests being processed exceeds the number of CPUs for any significant length of time, your server might be receiving more requests than it can efficiently process.

-   **Request durations:** This graph breaks down the average response times for different types of requests made by Puppet agents. This indicates how expensive catalog and report requests are compared to the other types of requests. It also provides a way to see changes in catalog compilation times when you modify your Puppet code. A sharp curve upward for all of the types of requests indicates an overloaded server, and they should trend downward after reducing the load on the server.

-   **Request ratios:** This graph shows how many requests of each type that Puppet Server has handled. Under normal circumstances, you should see about the same number of catalog, node, or report requests, because these all happen once per agent run. The number of file and file metadata requests correlate to how many remote file resources are in the agents' catalogs.

-   **Communications with PuppetDB:** This graph tracks the amount of time it takes Puppet Server to send data and requests for common operations to, and receive responses from, PuppetDB.

-   **JRubies**: This graph tracks how many JRubies are in use, how many are free, the mean number of free JRubies, and the mean number of requested JRubies.

    If the number of free JRubies is often less than one, or the mean number of free JRubies is less than one, Puppet Server is requesting and consuming more JRubies than are available. This overload reduces Puppet Server's performance. While this might simply be a symptom of an under-resourced server, it can also be caused by poorly optimized Puppet code or bottlenecks in the server's communications with PuppetDB if it is in use.

    If catalog compilation times have increased but PuppetDB performance remains the same, examine your Puppet code for potentially unoptimized code. If PuppetDB communication times have increased, tune PuppetDB for better performance or allocate more resources to it.

    If neither catalog compilation nor PuppetDB communication times are degraded, the Puppet Server process might be under-resourced on your server. If you have available CPU time and memory, [increase the number of JRuby instances](./tuning_guide.markdown) to allow it to allocate more JRubies. Otherwise, consider adding additional compile masters to distribute the catalog compilation load.

-   **JRuby Timers**: This graph tracks several JRuby pool metrics.

    -   The borrow time represents the mean amount of time that Puppet Server uses ("borrows") each JRuby from the pool.

    -   The wait time represents the total amount of time that Puppet Server waits for a free JRuby instance.

    -   The lock held time represents the amount of time that Puppet Server holds a lock on the pool, during which JRubies cannot be borrowed.

    -   The lock wait time represents the amount of time that Puppet Server waits to acquire a lock on the pool.

    These metrics help identify sources of potential JRuby allocation bottlenecks.

-   **Memory Usage**: This graph tracks how much heap and non-heap memory that Puppet Server uses.

-   **Compilation:** This graph breaks catalog compilation down into various phases to show how expensive each phase is on the master.

### Example Grafana dashboard excerpt

The following example shows only the `targets` parameter of a dashboard to demonstrate the full names of Puppet's exported Graphite metrics (assuming the Puppet Server instance has a domain of `master.example.com`) and a way to add targets directly to an exported Grafana dashboard's JSON content.

``` json
"panels": [
    {
        "span": 4,
        "editable": true,
        "type": "graphite",

...

        "targets": [
            {
                "target": "alias(puppetlabs.master.example.com.num-cpus,'num cpus')"
            },
            {
                "target": "alias(puppetlabs.master.example.com.http.active-requests.count,'active requests')"
            },
            {
                "target": "alias(puppetlabs.master.example.com.http.active-histo.mean,'average')"
            }
        ],
        "aliasColors": {},
        "aliasYAxis": {},
        "title": "Active Requests"
    }
]
```

See the [sample Grafana dashboard][] for a detailed example of how a Grafana dashboard accesses these exported Graphite metrics.

## Available Graphite metrics

The following HTTP and Puppet profiler metrics are available from the Puppet Server and can be added to your metrics reporting. Each metric is prefixed with `puppetlabs.<MASTER-HOSTNAME>`; for instance, the Grafana dashboard file refers to the `num-cpus` metric as `puppetlabs.<MASTER-HOSTNAME>.num-cpus`.

Additionally, metrics might be suffixed by fields, such as `count` or `mean`, that return more specific data points. For instance, the `puppetlabs.<MASTER-HOSTNAME>.compiler.mean` metric returns only the mean length of time it takes Puppet Server to compile a catalog.

To aid with reference, metrics in the list below are segmented into three groups:

-   **Statistical metrics:** Metrics that have all eight of these statistical analysis fields, in addition to the top-level metric:

    -   `max`: Its maximum measured value.

    -   `min`: Its minimum measured value.

    -   `mean`: Its mean, or average, value.

    -   `stddev`: Its standard deviation from the mean.

    -   `count`: An incremental counter.

    -   `p50`: The value of its 50th percentile, or median.

    -   `p75`: The value of its 75th percentile.

    -   `p95`: The value of its 95th percentile.

-   **Counters only:** Metrics that only count a value, or only have a `count` field.

-   **Other:** Metrics that have unique sets of available fields.

> **Note:** Puppet Server can export many, many metrics -- so many that enabling all of them at large installations can overwhelm Grafana servers. To avoid this, Puppet Server exports only a subset of its available metrics by default. This default set is designed to report the most relevant metrics for administrators monitoring performance and stability.
>
> To add to the default list of exported metrics, see [Modifying Puppet Server's exported metrics](#modifying-puppet-servers-exported-metrics).

Puppet Server exports each metric in the lists below by default.

### Statistical metrics

#### Compiler metrics

-   `puppetlabs.<MASTER-HOSTNAME>.compiler`: The time spent compiling catalogs. This metric represents the sum of the `compiler.compile`, `static_compile`, `find_facts`, and `find_node` fields.

    -   `puppetlabs.<MASTER-HOSTNAME>.compiler.compile`: The total time spent compiling dynamic (non-static) catalogs.

        To measure specific nodes and environments, see [Modifying Puppet Server's exported metrics](#modifying-puppet-servers-exported-metrics).

    -   `puppetlabs.<MASTER-HOSTNAME>.compiler.find_facts`: The time spent parsing facts.

    -   `puppetlabs.<MASTER-HOSTNAME>.compiler.find_node`: The time spent retrieving node data. If the Node Classifier (or another ENC) is configured, this includes the time spent communicating with it.

    -   `puppetlabs.<MASTER-HOSTNAME>.compiler.static_compile`: The time spent compiling [static catalogs][].

    -   `puppetlabs.<MASTER-HOSTNAME>.compiler.static_compile_inlining`: The time spent inlining metadata for static catalogs.

    -   `puppetlabs.<MASTER-HOSTNAME>.compiler.static_compile_postprocessing`: The time spent post-processing static catalogs.

#### Function metrics

-   `puppetlabs.<MASTER-HOSTNAME>.functions`: The amount of time during catalog compilation spent in function calls. The `functions` metric can also report any of the [statistical metrics](#available-graphite-metrics) fields for a single function by specifying the function name as a field.

    For example, to report the mean time spent in a function call during catalog compilation, use `puppetlabs.<MASTER-HOSTNAME>.functions.<FUNCTION-NAME>.mean`.

#### HTTP metrics

-   `puppetlabs.<MASTER-HOSTNAME>.http.active-histo`: A histogram of active HTTP requests over time.

-   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-catalog-/*/-requests`: The time Puppet Server has spent handling catalog requests, including time spent waiting for an available JRuby instance.

-   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-environment-/*/-requests`: The time Puppet Server has spent handling environment requests, including time spent waiting for an available JRuby instance.

-   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-environment_classes-/*/-requests`: The time spent handling requests to the [`environment_classes` API endpoint](./puppet-api/v3/environment_classes.markdown), which the Node Classifier uses to refresh classes.

-   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-environments-requests`: The time spent handling requests to the [`environments` API endpoint](https://puppet.com/docs/puppet/latest/http_api/http_environments.html) requests.

-   The following metrics measure the time spent handling file-related API endpoints:

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-file_bucket_file-/*/-requests`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-file_content-/*/-requests`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-file_metadata-/*/-requests`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-file_metadatas-/*/-requests`

-   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-node-/*/-requests`: The time spent handling node requests, which are sent to the Node Classifier. A bottleneck here might indicate an issue with the Node Classifier or PuppetDB.

-   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-report-/*/-requests`: The time spent handling report requests. A bottleneck here might indicate an issue with PuppetDB.

-   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-static_file_content-/*/-requests`: The time spent handling requests to the [`static_file_content` API endpoint](./puppet-api/v3/static_file_content.markdown) used by Direct Puppet with file sync.

#### JRuby metrics

Puppet Server uses an embedded JRuby interpreter to execute Ruby code. JRuby spawns parallel instances known as JRubies to execute Ruby code, which occurs during most Puppet Server activities.

See [Tuning JRuby on Puppet Server](./tuning_guide.markdown) for details on adjusting JRuby settings.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.borrow-timer`: The time spent with a borrowed JRuby.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.free-jrubies-histo`: A histogram of free JRubies over time. This metric's average value should greater than 1; if it isn't, [more JRubies](./tuning_guide.markdown) or another compile master might be needed to keep up with requests.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.lock-held-timer`: The time spent holding the JRuby lock.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.lock-wait-timer`: The time spent waiting to acquire the JRuby lock.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.requested-jrubies-histo`: A histogram of requested JRubies over time. This increases as the number of free JRubies, or the `free-jrubies-histo` metric, decreases, which can suggest that the server's capacity is being depleted.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.wait-timer`: The time spent waiting to borrow a JRuby.

#### PuppetDB metrics

The following metrics measure the time that Puppet Server spends sending or receiving data from PuppetDB.

-   `puppetlabs.<MASTER-HOSTNAME>.puppetdb.catalog.save`

-   `puppetlabs.<MASTER-HOSTNAME>.puppetdb.command.submit`

-   `puppetlabs.<MASTER-HOSTNAME>.puppetdb.facts.find`

-   `puppetlabs.<MASTER-HOSTNAME>.puppetdb.facts.search`

-   `puppetlabs.<MASTER-HOSTNAME>.puppetdb.report.process`

-   `puppetlabs.<MASTER-HOSTNAME>.puppetdb.resource.search`

### Counters only

#### HTTP metrics

-   `puppetlabs.<MASTER-HOSTNAME>.http.active-requests`: The number of active HTTP requests.

-   The following counter metrics report the percentage of each HTTP API endpoint's share of total handled HTTP requests.

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-catalog-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-environment-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-environment_classes-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-environments-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-file_bucket_file-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-file_content-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-file_metadata-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-file_metadatas-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-node-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-report-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-resource_type-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-resource_types-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-static_file_content-/*/-percentage`

    -   `puppetlabs.<MASTER-HOSTNAME>.http.puppet-v3-status-/*/-percentage`

-   `puppetlabs.<MASTER-HOSTNAME>.http.total-requests`: The total requests handled by Puppet Server.

#### JRuby metrics

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.borrow-count`: The number of successfully borrowed JRubies.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.borrow-retry-count`: The number of attempts to borrow a JRuby that must be retried.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.borrow-timeout-count`: The number of attempts to borrow a JRuby that resulted in a timeout.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.request-count`: The number of requested JRubies.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.return-count`: The number of JRubies successfully returned to the pool.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.num-free-jrubies`: The number of free JRuby instances. If this number is often 0, more requests are coming in than the server has available JRuby instances. To alleviate this, increase the number of JRuby instances on the Server or add additional compile masters.

-   `puppetlabs.<MASTER-HOSTNAME>.jruby.num-jrubies`: The total number of JRuby instances on the server, governed by the `max-active-instances` setting. See [Tuning JRuby on Puppet Server](./tuning_guide.markdown) for details.

### Other metrics

These metrics measure raw resource availability and capacity.

-   `puppetlabs.<MASTER-HOSTNAME>.num-cpus`: The number of available CPUs on the server.

-   `puppetlabs.<MASTER-HOSTNAME>.uptime`: The Puppet Server process's uptime.

-   Total, heap, and non-heap memory that's committed (`committed`), initialized (`init`), and used (`used`), and the maximum amount of memory that can be used (`max`).

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.total.committed`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.total.init`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.total.used`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.total.max`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.heap.committed`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.heap.init`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.heap.used`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.heap.max`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.non-heap.committed`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.non-heap.init`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.non-heap.used`

    -   `puppetlabs.<MASTER-HOSTNAME>.memory.non-heap.max`

For details about HTTP client metrics, which measure performance of Puppet Server's requests to other services, see [their documentation][HTTP client metrics].

### Modifying Puppet Server's exported metrics

In addition to the above default metrics, you can also export metrics measuring specific environments and nodes.

The `metrics.registries.puppetserver.metrics-allowed` parameter in [`metrics.conf`][] takes an array of strings representing the metrics you want to enable.

Omit the `puppetlabs.<MASTER-HOSTNAME>` prefix and field suffixes (such as `.count` or `.mean`) from metrics when adding them to this class. Instead, suffix the environment or node name as a field to the metric.

For example, to track the compilation time for the `production` environment, add `compiler.compile.production` to the `metrics-allowed` list. To track only the `my.node.localdomain` node in the `production` environment, add `compiler.compile.production.my.node.localdomain` to the `metrics-allowed` list.

Optional metrics include:

-   `compiler.compile.<ENVIRONMENT>` and `compiler.compile.<ENVIRONMENT>.<NODE-NAME>`, and all statistical fields suffixed to these (such as `compiler.compile.<ENVIRONMENT>.mean`).

-   `compiler.compile.evaluate_resources.<RESOURCE>`: Time spent evaluating a specific resource during catalog compilation.

