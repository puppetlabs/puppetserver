# SSL Server Certificate Change and Virtual IP Addresses

[SERVER-207] (https://tickets.puppetlabs.com/browse/SERVER-207) documents an
issue that has been seen with Puppet Server but is not present with the
Ruby-based Puppet master.  When the Puppet Server master needs to make an SSL
client connection and the connection target is a virtual ip address which is
load balanced to multiple backing servers, differences in the certificate that
the target server presents may lead to a connection failure.  The master may,
for example, be delivering a report to multiple PuppetDB servers behind the
load-balanced ip address.  The failure in the master's `puppetserver.log` file
may look like this:

```
2014-11-20 22:04:03,392 ERROR [c.p.h.c.SyncHttpClient] Error executing http request
javax.net.ssl.SSLHandshakeException: server certificate change is restrictedduring renegotiation
```

One difference between Puppet Server and the Ruby Puppet master is that Puppet
Server's SSL client connections will attempt to resume an SSL session, using the
session id provided from the server (PuppetDB, in this case), whereas a Ruby
Puppet master will not.

For the case that a load balancer is sitting between the Puppet master and
PuppetDB instances and a client connection is directed to a server which has no
registered session id for the session that the client is trying to resume, the
SSL handshake would need to be renegotiated.  The JDK, which underlies the
Puppet Server master, added a check for uniqueness of the server certificate
during a re-negotiation following a session resumption. This check is done as a
way to help mitigate the TLS triple handshake attack – see
https://secure-resumption.com.  The client connection is aborted if the check
fails.

There are at least a couple of immediate options:

* In order to avoid having the certificate check fail, all end servers could
present the same certificate.

It appears to be possible for the server certificates to use certificates which
are different but have some matching attributes – i.e., the ipAddress in the
SubjectAltName extension, the dNSName in the SubjectAltName extension, and/or
the subject and issuer.  See
http://hg.openjdk.java.net/bsd-port/bsd-port/jdk/rev/eabde5c42157#l1.186.  The
approach of having certificates with selectively matched attributes may not be
foolproof, though, in that a future JDK implementation may strengthen the
comparison to only accept an exact match for the full data in the certificate.

* To the `JAVA_ARGS` variable value in the `/etc/sysconfig/puppetserver`
configuration file, add `-Djdk.tls.allowUnsafeServerCertChange=true`.

The use of this property is also documented in
http://hg.openjdk.java.net/bsd-port/bsd-port/jdk/rev/eabde5c42157#l1.50.  While
this would appear to allow for the certificate equivalence check to be bypassed
entirely, this would also open up the Puppet master more fully to the TLS triple
handshake attack.  For this reason, this is probably not the best solution.

---

Additionally, we're considering adding the ability via configuration to
optionally turn off SSL session caching for the Jetty server (when hosting
Puppet Server and/or PuppetDB) and/or Puppet Server master client requests.
Several JIRA tickets have been filed to cover this work:

* [TK-124] (https://tickets.puppetlabs.com/browse/TK-124) - Disable SSL session
  caching in the Jetty server
* [TK-125] (https://tickets.puppetlabs.com/browse/TK-125) - Disable SSL session
  caching in the clj-http-client library that Puppet Server uses to make its
  client requests
* [SERVER-216] (https://tickets.puppetlabs.com/browse/SERVER-216) - Utilize work
  in TK-125 to allow SSL session caching to be disabled for Puppet Server client
  requests.

While the ability to disable SSL session caching would provide mitigation 
against the triple handshake attack, allow different certificates to be used on
each PuppetDB server, and provide for more backward compatible behavior with the
Ruby Puppet master, the approach would have performance trade-offs.  Not having
the ability to use session resumption would force a full SSL handshake to occur
on each connection for master requests.  If the load balancer were aggressively
redirecting consecutive requests to different PuppetDB servers, a full handshake
would inevitably be occurring anyway.  If the load balancer were to redirect at
least some requests for different connections back to the same server, however,
session resumption would otherwise have had a performance benefit.