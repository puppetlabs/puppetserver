---
layout: default
title: "Puppet Server Configuration Files: global.conf"
canonical: "/puppetserver/latest/config_file_global.html"
---

The `global.conf` file contains global configuration settings for Puppet Server. For an overview, see [Puppet Server Configuration](./configuration.markdown).

You shouldn't typically need to make changes to this file. However, you can change the `logging-config` path for the logback logging configuration file if necessary. For more information about the logback file, see <http://logback.qos.ch/manual/configuration.html>.

## Example

~~~
global: {
    logging-config: /etc/puppetlabs/puppetserver/logback.xml
}
~~~
