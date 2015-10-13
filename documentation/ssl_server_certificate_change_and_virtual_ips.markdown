---
layout: default
title: "Puppet Server: Known Issues: SSL Server Certificate Change and Virtual IP Addresses"
canonical: "/puppetserver/latest/ssl_server_certificate_change_and_virtual_ips.html"
---
[pe_db_instructions]: https://docs.puppetlabs.com/pe/latest/release_notes_known_issues.html#puppetdb-behind-a-load-balancer-causes-puppet-server-errors

Puppet Server can often encounter `server certificate change is restricted` errors when it makes HTTPS requests to a group of load-balanced servers behind a virtual IP address. This page describes the issue, workarounds for the issue, and our future plans for handling the issue.

The behavior described in this page was identified in [SERVER-207](https://tickets.puppetlabs.com/browse/SERVER-207).

## Summary of the Problem

The JDK handles HTTPS client connections differently from Ruby, so Puppet Server has some behaviors that you wouldn't see with a Passenger-based Puppet master.

Specifically, if Puppet Server makes multiple HTTPS requests to the same server, it attempts to resume an SSL session using the session ID provided from the server. If that server doesn't have a suitable session ID, Puppet Server and the server try to renegotiate the session.

During the renegotiation, Puppet Server checks to make sure the server is using the same certificate (to [mitigate the TLS triple handshake attack](https://secure-resumption.com)). If that check fails, it aborts the connection.

For example, if Puppet Server is configured to use a load-balanced group of PuppetDB servers, and those servers all use different certificates, some of the certificate checks will fail, and Puppet Server will abort those connections.

These connection failures may look like this in the `puppetserver.log` file:

~~~
2014-11-20 22:04:03,392 ERROR [c.p.h.c.SyncHttpClient] Error executing http request
javax.net.ssl.SSLHandshakeException: server certificate change is restricted during renegotiation
~~~

## Working Around the Problem

### Recommended Workaround

If you need Puppet Server to act as a client to a load-balanced HTTPS service (e.g., multiple PuppetDB servers), your best option right now is to have all of the servers behind the load balancer present the same certificate.

There appear to be ways to fulfill the renegotiation check with certificates that only partially match ([see here for more info](http://hg.openjdk.java.net/bsd-port/bsd-port/jdk/rev/eabde5c42157#l1.186)), but these might not be foolproof, especially since future JDK implementations might disallow these partial matches. The most reliable way is to simply use the same certificates.

The Puppet Enterprise documentation has [instructions for configuring multiple PuppetDB][pe_db_instructions] servers to use a single certificate, but note that this configuration isn't necessarily supported.


### Alternate Workaround

It's also possible to configure the JDK to allow server certificate changes. You can do this by editing the `/etc/sysconfig/puppetserver` file and adding `-Djdk.tls.allowUnsafeServerCertChange=true` to the value of the `JAVA_ARGS` variable.

We don't recommend this workaround, however, because it can make Puppet Server more vulnerable to the TLS triple handshake attack.

The use of the `allowUnsafeServerCertChange` property is documented in
<http://hg.openjdk.java.net/bsd-port/bsd-port/jdk/rev/eabde5c42157#l1.50>.


## Future Plans

We're considering optional settings to turn off SSL session caching for Puppet Server's client requests or for the Jetty server when hosting Puppet Server or PuppetDB. Several JIRA tickets have been filed to cover this work:

* [TK-124](https://tickets.puppetlabs.com/browse/TK-124): Disable SSL session
  caching in the Jetty server
* [TK-125](https://tickets.puppetlabs.com/browse/TK-125): Disable SSL session
  caching in the clj-http-client library that Puppet Server uses to make its
  client requests
* [SERVER-216](https://tickets.puppetlabs.com/browse/SERVER-216): Utilize work
  in TK-125 to allow SSL session caching to be disabled for Puppet Server client
  requests.

This approach might have performance trade-offs, although if the load balancer distributes requests evenly among all of its servers, issues should be minimal.
