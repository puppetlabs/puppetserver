---
layout: default
title: "Puppet Server: Index"
canonical: "/puppetserver/latest/"
---

Puppet Server is the next-generation application for managing Puppet agents.

> **Note:** For information about configuring and tuning settings specific to [Puppet Enterprise](https://docs.puppet.com/pe/), see [its documentation](https://docs.puppet.com/pe/latest/config_puppetserver.html).

* [**About Puppet Server**](./services_master_puppetserver.markdown)
    * [Release notes](./release_notes.markdown)
    * [Deprecated features](./deprecated_features.markdown)
    * [Notable differences vs. the Apache/Passenger stack](./puppetserver_vs_passenger.markdown)
    * [Compatibility with Puppet agent](./compatibility_with_puppet_agent.markdown)
* [**Installing Puppet Server**](./install_from_packages.markdown)
* [**Configuring Puppet Server**](./configuration.markdown)
    * [global.conf](./config_file_global.markdown)
    * [webserver.conf](./config_file_webserver.markdown)
    * [web-routes.conf](./config_file_web-routes.markdown)
    * [puppetserver.conf](./config_file_puppetserver.markdown)
    * [auth.conf](./config_file_auth.markdown)
        * [Migrating deprecated authentication rules](./config_file_auth_migration.markdown)
    * [logback.xml](./config_file_logbackxml.markdown)
        * [Advanced logging configuration](./config_logging_advanced.markdown)
    * [master.conf](./config_file_master.markdown) (deprecated)
    * [ca.conf](./config_file_ca.markdown) (deprecated)
    * [Differing behavior in puppet.conf](./puppet_conf_setting_diffs.markdown)
* **Using and extending Puppet Server**
    * [Subcommands](./subcommands.markdown)
    * [Using Ruby gems](./gems.markdown)
    * [Using an external certificate authority](./external_ca_configuration.markdown)
    * [External SSL termination](./external_ssl_termination.markdown)
    * [Tuning guide](./tuning_guide.markdown)
    * [Restarting Puppet Server](./restarting.markdown)
* **Known issues and workarounds**
    * [Known issues](./known_issues.markdown)
    * [SSL problems with load-balanced PuppetDB servers ("Server Certificate Change" error)](./ssl_server_certificate_change_and_virtual_ips.markdown)
* **Administrative API endpoints**
    * [Environment cache](./admin-api/v1/environment-cache.markdown)
    * [JRuby pool](./admin-api/v1/jruby-pool.markdown)
* **Server-specific Puppet API endpoints**
    * [Environment classes](./puppet-api/v3/environment_classes.markdown)
    * [Static file content](./puppet-api/v3/static_file_content.markdown)
* **Status API endpoints**
    * [Status services](./status-api/v1/services.markdown)
* **Developer information**
    * [Debugging](./dev_debugging.markdown)
    * [Running from source](./dev_running_from_source.markdown)
    * [Tracing code events](./dev_trace_func.markdown)
