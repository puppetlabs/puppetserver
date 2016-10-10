---
layout: default
title: "Puppet Server: Advanced Logging Configuration"
canonical: "/puppetserver/latest/config_logging_advanced.html"
---

Puppet Server uses the [Logback](http://logback.qos.ch/) library to handle all of its logging. Logback configuration settings are stored in the [`logback.xml`](./config_file_logbackxml.markdown) file, which is located at `/etc/puppetlabs/puppetserver/logback.xml` by default.

You can configure Logback to log messages in JSON format, which makes it easy to send them to other logging backends, such as Logstash.

## Configuring Puppet Server for use with Logstash

There are a few steps necessary to setup your Puppet Server logging for use with Logstash. The first step is to modify your logging configuration so that Puppet Server is logging in a JSON format. After that, you'll configure an external tool to monitor these JSON files and send the data to Logstash (or another remote logging system).

### Configuring Puppet Server to log to JSON

Before you configure Puppet Server to log to JSON, consider the following:

* Do you want to configure Puppet Server to *only* log to JSON, instead of the default plain-text logging? Or do you want to have JSON logging *in addition to* the default plain-text logging?
* Do you want to set up JSON logging *only* for the main Puppet Server logs (`puppetserver.log`), or *also* for the HTTP access logs (`puppetserver-access.log`)?
* What kind of log rotation strategy do you want to use for the new JSON log files?

The following examples show how to configure Logback for:

* logging to both JSON and plain-text
* JSON logging both the main logs and the HTTP access logs
* log rotation on the JSON log files

Adjust the example configuration settings to suit your needs.

> **Note:** Puppet Server also relies on Logback to manage, rotate, and archive Server log files. Logback archives Server logs when they exceed 200MB, and when the total size of all Server logs exceeds 1GB, it automatically deletes the oldest logs.

#### Adding a JSON version of the main Puppet Server logs

Logback writes logs using components called [appenders](http://logback.qos.ch/manual/appenders.html). The example code below uses `RollingFileAppender` to rotate the log files and avoid consuming all of your storage.

1.  To configure Puppet Server to log its main logs to a second log file in JSON format, add an appender section like the following example to your `logback.xml` file, at the same level in the XML as existing appenders. The order of the appenders does not matter.

    ``` xml
    <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/puppetlabs/puppetserver/puppetserver.log.json</file>

        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/puppetlabs/puppetserver/puppetserver.log.json.%d{yyyy-MM-dd}</fileNamePattern>
            <maxHistory>5</maxHistory>
        </rollingPolicy>

        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    ```

2.  Activate the appended by adding an `appender-ref` entry to the `<root>` section of `logback.xml`:

    ``` xml
    <root level="info">
        <appender-ref ref="FILE"/>
        <appender-ref ref="JSON"/>
    </root>
    ```

3.  If you decide you want to log *only* the JSON format, comment out the other `appender-ref` entries.

`LogstashEncoder` has many configuration options, including the ability to modify the list of fields that you want to include, or give them different field names. For more information, see the [Logstash Logback Encoder Docs](https://github.com/logstash/logstash-logback-encoder/blob/master/README.md#loggingevent-fields).

#### Adding a JSON version of the Puppet Server HTTP Access logs

To add JSON logging for HTTP requests:

1.  Add the following Logback appender section to the `request-logging.xml` file:

    ``` xml
    {% raw %}
    <appender name="JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/puppetlabs/puppetserver/puppetserver-access.log.json</file>

        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/puppetlabs/puppetserver/puppetserver-access.log.json.%d{yyyy-MM-dd}</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>

        <encoder class="net.logstash.logback.encoder.AccessEventCompositeJsonEncoder">
            <providers>
                <version/>
                <pattern>
                    <pattern>
                        {
                          "@timestamp":"%date{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}",
                          "clientip":"%remoteIP",
                          "auth":"%user",
                          "verb":"%requestMethod",
                          "requestprotocol":"%protocol",
                          "rawrequest":"%requestURL",
                          "response":"#asLong{%statusCode}",
                          "bytes":"#asLong{%bytesSent}",
                          "total_service_time":"#asLong{%elapsedTime}",
                          "request":"http://%header{Host}%requestURI",
                          "referrer":"%header{Referer}",
                          "agent":"%header{User-agent}",

                          "request.host":"%header{Host}",
                          "request.accept":"%header{Accept}",
                          "request.accept-encoding":"%header{Accept-Encoding}",
                          "request.connection":"%header{Connection}",

                          "puppet.client-verify":"%header{X-Client-Verify}",
                          "puppet.client-dn":"%header{X-Client-DN}",
                          "puppet.client-cert":"%header{X-Client-Cert}",

                          "response.content-type":"%responseHeader{Content-Type}",
                          "response.content-length":"%responseHeader{Content-Length}",
                          "response.server":"%responseHeader{Server}",
                          "response.connection":"%responseHeader{Connection}"
                        }
                    </pattern>
                </pattern>
            </providers>
        </encoder>
    </appender>
    {% endraw %}
    ```

2.  Add a corresponding `appender-ref` in the `configuration` section:

    ``` xml
    <appender-ref ref="JSON"/>
    ```

For more information about options available for the `pattern` section, see the [Logback Logstash Encoder Docs](https://github.com/logstash/logstash-logback-encoder/blob/master/README.md#accessevent-fields).

### Sending the JSON data to Logstash

After configuring Puppet Server to log messages in JSON format, you must also configure it to send the logs to Logstash (or another external logging system).  There are several different ways to approach this:

* Configure Logback to send the data to Logstash directly, from within Puppet Server. See the Logstash-Logback encoder docs on how to send the logs by [TCP](https://github.com/logstash/logstash-logback-encoder/blob/master/README.md#tcp) or [UDP](https://github.com/logstash/logstash-logback-encoder/blob/master/README.md#udp). Note that TCP comes with the risk of bottlenecking Puppet Server if your Logstash system is busy, and UDP might silently drop log messages.
* [Filebeat](https://www.elastic.co/products/beats/filebeat) is a tool from Elastic for shipping log data to Logstash.
* [Logstash Forwarder](https://github.com/elastic/logstash-forwarder) is an earlier tool from Elastic with similar capabilities.
