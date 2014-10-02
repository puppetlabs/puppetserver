## 0.2.2
 * (SERVER-13) Fix for file descriptor leak during report processing
 * (SERVER-7) Add licensing and copyright info
 * HTTP client connections from the master use the `localcacert` puppet.conf
   setting to find the CA certs to use for validating a server.  Previously, the
   `cacert` puppet.conf setting was used to find the CA certs used to validate
   the server.
 
## 0.2.1
 * (SERVER-9) Privileged data is accessible to non-privileged local users [CVE-2014-7170]

## 0.2.0
 Initial Open Source Release
