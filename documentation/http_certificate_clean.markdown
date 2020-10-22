---
layout: default
title: "Puppet Server: Puppet HTTP API: Certificate Clean"
canonical: "/puppetserver/latest/puppet-api/v3/task-detail.html"
---
Certificate Clean
===============

The `certificate clean` endpoint of the CA API allows the user to revoke and delete a list
of certificates with a single request.

```
PUT /puppet-ca/v1/clean
Content-Type: application/json
```
The body takes one required key, `certnames`, a list of certificates to clean. Each cert
in the list will be revoked, then the associated certficate file will be deleted from the CA.

If a given certname does not have an associated signed cert on the CA, the response body will
call this out, but the request will not error.

### Example

```
PUT /puppet-ca/v1/clean
Content-Type: application/json
Content-Length: 58

{"certnames":["agent1.example.net","agent2.example.net"]}

HTTP/1.1 200 OK
Context-Type: text/plain
Successfully cleaned all certificates.
```
Both certs will be revoked, then have their files deleted.

```
PUT /puppet-ca/v1/clean
Content-Type: application/json
Content-Length: 58

{"certnames":["missing.example.net","agent1.example.net"]}

HTTP/1.1 200 OK
Context-Type: text/plain
The following certs do not exist and cannot be revoked: ["missing.example.net"]
```
The missing cert is skipped, the other is revoked and deleted.
