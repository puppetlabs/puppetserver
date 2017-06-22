---
layout: default
title: "Puppet Server Configuration Files: metrics.conf"
canonical: "/puppetserver/latest/config_file_metrics.html"
---

The `metrics.conf` file configures Puppet Server's [metrics services](./puppet_server_metrics.markdown) and [v2 metrics API](./metrics-api/v2/metrics_api.markdown).

## Settings

All settings in the file are contained in a HOCON `metrics` section.

-   `server-id`: A unique identifier to be used as part of the namespace for metrics that this server produces.

-   `registries`: A section that contains settings to control which metrics are reported, and how they're reported.
    -   `<REGISTRY NAME>`: A section named for a registry that contains its settings. In Puppet Server's case, this section should be `puppetserver`.
        -   `metrics-allowed`: An array of metrics to report. See the [metrics documentation](./puppet_server_metrics.markdown) for details about individual metrics.
        -   `reporters`: Can contain `jmx` and `graphite` sections with a single Boolean `enabled` setting to enable or disable each reporter type.
-   `reporters`: Configures reporters that distribute metrics to external services or viewers.
    -   `graphite`: Contains settings for the Graphite reporter.
        -   `host`: A string containing the Graphite server's hostname or IP address.
        -   `port`: Contains the Graphite service's port number.
        -   `update-interval-seconds`: Sets the interval on which Puppet Server will send metrics to the Graphite server.

## Example

Puppet Server ships with a default `metrics.conf` file in Puppet Server's `conf.d` directory, similar to the below example with additional comments.

```
metrics: {
    server-id: localhost
    registries: {
        puppetserver: {
            # specify metrics to allow in addition to those in the default list
            #metrics-allowed: ["compiler.compile.production"]

            reporters: {
                jmx: {
                    enabled: true
                }
                # enable or disable Graphite metrics reporter
                #graphite: {
                #    enabled: true
                #}
            }

        }
    }

    reporters: {
        #graphite: {
        #    # graphite host
        #    host: "127.0.0.1"
        #    # graphite metrics port
        #    port: 2003
        #    # how often to send metrics to graphite
        #    update-interval-seconds: 5
        #}
    }
}
```
