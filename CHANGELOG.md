## 0.4.0
This release contains improvements based on feedback from the community and
PuppetLabs QA testing. It is the release which is incorporated into Puppet
Enterprise 3.7 and has usability and correctness improvements, mainly around
SSL and our interaction with systemd. Notable changes:

  * (SERVER-89) The Puppet Server CA now creates a 'puppet' Subject Alternate 
    Name for master certificates for closer compatibility with the Ruby CA.
  * (SERVER-86) The CA no longer uses the 'ca_pub.pem' (which isn't guaranteed
    to exist) when signing or revoking; instead it extracts the key from the 
    certificate directly (which IS guaranteed to be there).
  * (SERVER-70, SERVER-8, SERVER-84) Improvements around packaging will make 
    the Puppet Server behave better under OSes which use systemd and will now 
    preserve local changes to the /etc/sysconfig/puppetserver config on 
    upgrade.

For a full list of bugs fixed in this release, check out the JIRA release page:
https://tickets.puppetlabs.com/browse/SERVER/fixforversion/12014

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
