## 0.3.0
This is the first feature update since the initial Puppet Server release.
Notable user-facing improvements are:

 * (SERVER-18, SERVER-39) Puppet Server now supports externally-terminated SSL 
   in the same way as external termination on Apache+Passenger does.
 * (SERVER-4) Improve error messages and user feedback when starting on systems
   with low memory. (We recommend at least 2GB RAM)
 * (SERVER-43) Add support for HTTP "basic" authentication; this was preventing 
   the 'http' report processor used by Dashboard from working.

For a full list of bugs fixed in the release, check out this JIRA page: 
https://tickets.puppetlabs.com/browse/SERVER/fixforversion/11955

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
