---
layout: default
title: "Puppet Server: Advanced Logging Configuration"
canonical: "/puppetserver/latest/config_logging_advanced.html"
---

Puppet Server uses the very flexible, very powerful [Logback](http://logback.qos.ch/) library under the hood to handle all of our logging needs.  Logback can be configured via the `logback.xml` file, which is located at `/etc/puppetlabs/puppetserver/logback.xml` by default.

Among the many interesting and useful capabilities of logback is the ability to configure it to log messages in a JSON format, which makes it easy to send them over to other logging backends such as logstash.

## Configuring Puppet Server for use with Logstash

There are a few steps necessary to get your Puppet Server logging set up for use with logstash.  The first step is to modify your logging configuration so that Puppet Server is logging in a JSON format.  After that, you'll need to configure an external tool to monitor these JSON files and send the data to logstash (or another remote logging system).

### Configuring Puppet Server to log to JSON

There are a handful of considerations you may want to take into account before configuring Puppet Server to log to JSON:

* Do you want to configure Puppet Server to *only* log to JSON?  In lieu of the default plain-text logging?  Or do you want to add JSON logging *in addition to* the default plain-text logging?
* Do you want to set up JSON logging only for the main Puppet Server logs (i.e. `puppetserver.log`), or also for the HTTP access logs (i.e. `puppetserver-access.log`)?
* What kind of log rotation strategy do you want to use for the new JSON log files?

In this section we'll show some examples of setting up your configuration for logging both the main logs and the HTTP access logs in JSON, *in addition to* leaving the main text-based log files in place.  We'll also configure logback to do log rotation on these new JSON files.  Hopefully you can modify these examples to suit your own needs, based on your answers to the three questions above.

**NOTE**: Puppet Server's packaging sets up `logrotate` management of the main `puppetserver.log` file automatically, which is why you don't see any of the `logback` support for log rotation configured in our default `logback.xml` file.  However, the Puppet Server packaging does *not* include any `logrotate` integration for these new JSON log files that we'll be setting up, so that is why we will show examples of how to use `logback` to do that.  If you were so inclined, you could omit those portions of these configuration examples, and configure `logrotate` to manage the log rotation for your new JSON files.

#### Adding a JSON version of the main Puppet Server logs

To configure Puppet Server to log its main logs to a second log file, in JSON format, simply add a section like this to your `logback.xml` file (it should be added at the same level in the XML as your existing appender/appenders, but the order of the appenders does not matter):

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

Logback writes logs using components called [appenders](http://logback.qos.ch/manual/appenders.html).  Note that the above configuration includes the `RollingFileAppender`, to rotate the log files and ensure they don't fill up your disk.  Also, note that there are a lot of configuration options available for the `LogstashEncoder` above, including the ability to modify the list of fields that you wish to include, or give them different field names.  For more info see the [Logstash Logback Encoder Docs](https://github.com/logstash/logstash-logback-encoder/blob/master/README.md#loggingevent-fields).

The above XML snippet will create an appender that will log to the specified JSON file when active.  In order to activate the appender, you also need to add an `appender-ref`, e.g. in the `<root>` section of your `logback.xml`:

``` xml
<root level="info">
        <appender-ref ref="FILE"/>
        <appender-ref ref="JSON"/>
</root>
```

You could also comment out or remove the other `appender-ref`s, if you decided you *only* wanted to log the JSON format.

#### Adding a JSON version of the Puppet Server HTTP Access logs

To add JSON logging for Puppet Server's HTTP requests, modify the `request-logging.xml` file.  Here's an example of adding a logback appender there:

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

For more information on options available for the `pattern` section, see the [Logback Logstash Encoder Docs](https://github.com/logstash/logstash-logback-encoder/blob/master/README.md#accessevent-fields).

You also need to deal with the `appender-ref`s in this config file as well, e.g. by adding something like this inside the `configuration` section of the file:

``` xml
<appender-ref ref="JSON"/>
```

### Sending the JSON data to Logstash

If you've followed the steps above, you now have Puppet Server configured to log to files on disk in a JSON format.  The next step is to configure your system to send the logs to logstash (or another external logging system).  There are several different ways to approach this; here are a few for you to consider:

* Configure logback to send the data to logstash directly, from within Puppet Server.  Checkout the logstash-logback-encoder docs on how to send the logs via [TCP](https://github.com/logstash/logstash-logback-encoder/blob/master/README.md#tcp) or [UDP](https://github.com/logstash/logstash-logback-encoder/blob/master/README.md#udp).  Note that TCP comes with the risk of bottlenecking Puppet Server if your logstash system is backed up, and UDP comes with the risk of silently dropping log messages.
* [Filebeat](https://www.elastic.co/products/beats/filebeat) is Elastic's latest tool for shipping log data to logstash.
* [Logstash Forwarder](https://github.com/elastic/logstash-forwarder) is an earlier tool that Elastic provided that has some of the same capabilities.
