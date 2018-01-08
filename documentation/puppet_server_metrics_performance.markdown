---
layout: default
title: "Applying metrics to improve performance"
canonical: "/puppetserver/latest/puppet_server_metrics_performance.html"
---

[metrics]: ./puppet_server_metrics.markdown
[tuning guide]: ./tuning_guide.markdown
[metrics API]: ./metrics-api/v1/metrics_api.markdown
[status API]: ./status-api/v1/services.markdown
[developer dashboard]: #using-the-developer-dashboard
[Graphite]: https://graphiteapp.org
[Grafana]: http://grafana.org
[sample Grafana dashboard]: ./sample-puppetserver-metrics-dashboard.json
[puppetserver.conf]: ./config_file_puppetserver.markdown
[HTTP client metrics]: ./http_client_metrics.markdown

Puppet Server produces [several types of metrics][metrics] that administrators can use to identify performance bottlenecks or capacity issues. Interpreting this data is largely up to you and depends on many factors unique to your installation and usage, but there are some common trends in metrics that you can use to make Puppet Server function better.

> **Note:** This document assumes that you are already familiar with Puppet Server's [metrics tools][metrics], which report on relevant information, and its [tuning guide][], which provides instructions for modifying relevant settings. To put it another way, this guide attempts to explain questions about "why" Puppet Server performs the way it does for you, while your servers are the "who", Server [metrics][] help you track down exactly "what" is affecting performance, and the [tuning guide][] explains "how" you can improve performance.
>
> **If you're using Puppet Enterprise (PE),** consult its documentation instead of this guide for PE-specific requirements, settings, and instructions:
>
> -   [Large environment installations (LEI)](https://puppet.com/docs/pe/latest/installing/hardware_requirements.html#large-environment-hardware-requirements)
> -   [Compile masters](https://puppet.com/docs/pe/latest/installing/installing_compile_masters.html)
> -   [Load balancing](https://puppet.com/docs/pe/latest/installing/installing_compile_masters.html#using-load-balancers-with-compile-masters)
> -   [High availability](https://puppet.com/docs/pe/latest/high_availability/high_availability_overview.html)

## Measuring capacity with JRubies

Puppet Server uses JRuby, which rations server resources in the form of JRubies that Puppet Server consumes as it handles requests. A simple way of explaining Puppet Server performance is to remember that your Server infrastructure must be capable of providing enough JRubies for the amount of activity it handles. Anything that reduces or limits your server's capacity to produce JRubies also degrades Puppet Server's performance.

Several factors can limit your Server infrastructure's ability to produce JRubies.

### Request-handling capacity

If your free JRubies are 0 or fewer, your server is receiving more requests for JRubies than it can provide, which means it must queue those requests to wait until resources are available. Puppet Server performs best when the average number of free JRubies is above 1, which means Server always has enough resources to immediately handle incoming requests.

There are two indicators in Puppet Server's metrics that can help you identify a request-handling capacity issue:

-   **Average JRuby Wait Time:** This refers to the amount of time Puppet Server has to wait for an available JRuby to become available, and increases when each JRuby is held for a longer period of time, which reduces the overall number of free JRubies and forces new requests to wait longer for available resources.
-   **Average JRuby Borrow Time:** This refers to the amount of time that Puppet Server "holds" a JRuby as a resource for a request, and increases because of other factors on the server.

If wait time increases but borrow time stays the same, your Server infrastructure might be serving too many agents. This indicates that Server can easily handle requests but is receiving too many at once to keep up.

If both wait and borrow times are increasing, something else on your server is causing requests to take longer to process. The longer borrow times suggest that Puppet Server is struggling more than before to process requests, which has a cascading effect on wait times. Correlate borrow time increases with other events whenever possible to isolate what activities might cause them, such as a Puppet code change.

If you are setting up Puppet Server for the first time, start by increasing your Server infrastructure's capacity through additional JRubies (if your server has spare CPU and memory resources) or compile masters until you have more than 0 free JRubies, and your average number of free JRubies are at least 1. Once your system can handle its request volume, you can start looking into more specific performance improvements.

#### Adding more JRubies

If you must add JRubies, remember that Puppet Server is tuned by default to use one fewer than your total number of CPUs, with a maximum of 4 CPUs, for the number of available JRubies. You can change this by setting `max-active-instances` in [`puppetserver.conf`][puppetserver.conf], under the `jruby-puppet` section.

Each JRuby also has a certain amount of persistent memory overhead required in order to load both Puppet's Ruby code and your Puppet code. In other words, your available memory sets a baseline limit to how much Puppet code you can process. Catalog compilation can consume more memory, and Puppet Server's total memory usage depends on the number of agents being served, how frequently those agents check in, how many resources are being managed on each agent, and the complexity of the manifests and modules in use.

As a general rule, adding a JRuby requires a bare minimum of 40MB of memory under JRuby 1.7, and at least 60MB under JRuby9k if the `jruby-puppet.compile-mode` setting in [`puppetserver.conf`][puppetserver.conf] is set to `off` --- the amount of memory for the scripting container, plus Puppet's Ruby code, plus additional memory overhead --- just to compile an nearly empty catalog.

For real-world catalogs, you can generally add an absolute minimum of 15MB for each additional JRuby. We calculated this amount by comparing a minimal catalog compilation to compiling a catalog for a [basic role](https://github.com/puppetlabs/puppetlabs-puppetserver_perf_control/blob/production/site/role/manifests/by_size/small.pp) that installs Tomcat and Postgres servers.

Your Puppet-managed infrastructure is probably larger and more complex than that test scenario, and every complication adds more to each additional JRuby's memory requirements. (For instance, we recommend assuming that Puppet Server will use [at least 512MB per JRuby](https://puppet.com/docs/pe/latest/configuring/config_puppetserver.html) while under load.) You can calculate a similar value unique to your infrastructure by measuring `puppetserver` memory usage during your infrastructure's catalog compilations and comparing it to compiling a minimal catalog for a similar number of nodes.

The `jruby-metrics` section of the [status API][] endpoint also lists the `requested-instances`, which shows what requests have come in that are waiting to borrow a JRuby instance. This part of the status endpoint lists the lock's status, how many times it has been requested, and how long it has been held for. If it is currently being held and has been held for a while, you might see requests starting to stack up in the `requested-instances` section.

#### Adding compile masters

If you don't have the additional capacity on your master to add more JRubies, you'll want to add another compile master to your Server infrastructure. See [Scaling Puppet Server with compile masters](./scaling_puppet_server.markdown).

### HTTP request delays

If JRuby metrics appear to be stable, performance issues might originate from lag in server requests, which also have a cascading effect on other metrics. HTTP metrics in the [status API][], and the requests graph in the [Grafana dashboard](./puppet_server_metrics.markdown), can help you determine when and where request times have increased.

HTTP metrics include the total time for the server to handle the request, including waiting for a JRuby instance to become available. Once JRuby borrow time increases, wait time also increases, so once borrow time for *one* type of request increases, wait times for *all* requests increases.

Catalog compilation, which is graphed on the [sample Grafana dashboard][], most commonly increases request times, because there are many points of potential failure or delay in a catalog compilation. Several things could cause catalog compilation lengthen JRuby borrow times.

-   A Puppet code change, such as a faulty or complex new function. The Grafana dashboard should show if functions start taking significantly longer, and the experimental dashboard and [status API][] endpoint also list the lengthiest function calls (showing the top 10 and top 40, respectively) based on aggregate execution times.
-   Adding many file resources at once.

In cases like these, there might be more efficient ways to author your Puppet code, you might be extending Puppet to the point where you need to add JRubies or compile masters even if you aren't adding more agents.

Slowdowns in PuppetDB can also cause catalog compilations to take more time: if you use exported resources or the `puppetdb_query` function and PuppetDB has a problem, catalog compilation times will increase.

Puppet Server also sends agents' facts and the compiled catalog to PuppetDB during catalog compilation. The [status API][] for the master service reports metrics for these operations under [`http-client-metrics`][HTTP client metrics], and in the Grafana dashboard in the "External HTTP Communications" graph.

Puppet Server also requests facts as HTTP requests while handling a node request, and submits reports via HTTP requests while handling of a report request. If you have an HTTP report processor set up, the Grafana dashboard shows metrics for `Http report processor,` as does the [status API][] endpoint under `http-client-metrics` in the master service, for metric ID `['puppet', 'report', 'http']`. Delays in the report processor are passed on to Puppet Server.

### Memory leaks and usage

A memory leak or increased memory pressure can stress Puppet Server's available resources. In this case, the Java VM will spend more time doing garbage collection, causing the GC time and GC CPU % metrics to increase. These metrics are available in the [developer dashboard][] and [status API][] endpoint, as well as in the mbeans metrics available from both the [`/metrics/v1/mbeans`](./metrics-api/v1/metrics_api.markdown) or [`/metrics/v2/`](./metrics-api/v2/metrics_api.markdown) endpoints.

If you can't identify the source of a memory leak, setting the `max-requests-per-instance` setting in [`puppetserver.conf`][puppetserver.conf] to something other than the default of 0 limits the number of requests a JRuby handles during its lifetime and enables automatic JRuby flushing. Enabling this setting reduces overall performance, but if you enable it and no longer see signs of persistent memory leaks, check your module code for inefficiencies or memory-consuming bugs.