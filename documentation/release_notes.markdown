## 1.0.0
This release is the official "one point oh" version of Puppet Server. In
accordance with the [Semantic Versioning](http://semver.org) specification,
we're declaring the existing public API of this version to be the
baseline for backwards-incompatible changes, which will trigger another 
major version number. (No backwards-incompatible changes were introduced 
between 0.4.0 and this version.)

In addition, the following features were added:

 * (SERVER-151, SERVER-150) Created a HTTP endpoint to trigger a complete
   refresh of the entire JRuby pool.
 * (SERVER-204) Added CLI tools to execute the `ruby` and `irb` commands using
   Puppet server's JRuby environment.
 * (SERVER-221) Initialize run_mode earlier
 * (SERVER-114, SERVER-112) Added a HTTP endpoint to trigger a flush of the 
   Puppet environment cache.

For a list of all changes in this release, check out the JIRA page:
https://tickets.puppetlabs.com/browse/SERVER/fixforversion/12023/

## Puppet Server 0.4.0
This release contains improvements based on feedback from the community and
Puppet Labs QA testing. It has usability and correctness improvements, mainly
around SSL and our interaction with systemd. Notable changes:

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

## Puppet Server 0.3.0
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

## Puppet Server 0.2.2
* (SERVER-13) Fix for file descriptor leak during report processing
* (SERVER-7) Add licensing and copyright info
* HTTP client connections from the master use the `localcacert` puppet.conf
  setting to find the CA certs to use for validating a server.  Previously, the
  `cacert` puppet.conf setting was used to find the CA certs used to validate
  the server.

## Puppet Server 0.2.1
* (SERVER-9) Privileged data is accessible to non-privileged local users [CVE-2014-7170]

## Puppet Server 0.2.0
 Initial Open Source Release
