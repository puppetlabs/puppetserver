Puppet Server: Installing From Packages
====

### System Requirements

By default, the Puppet Server will be configured to use 2GB of RAM.
If you'd like to just play around with an installation on a Virtual Machine,
this much memory is not necessary.

To configure the Puppet Server memory allocation, modify 
`/etc/sysconfig/puppetserver` and look for the following line:

        # Modify this if you'd like to change the memory allocation, enable JMX, etc
        JAVA_ARGS="-Xms2g -Xmx2g"

Restart the `puppetserver` service after making any changes to this file.

Quick Start
----

1. [Enable the Puppet Labs package repositories][repodocs] (if you haven't already)
2. Stop the existing Puppet Master service
   
        service puppetmaster stop
        
   NOTE if running under Apache, you'll instead need to disable the puppetmaster
   vhost and restart the `httpd` service

3. Install the Puppet Server package

        yum install puppetserver
    
   Or
   
        apt-get install puppetserver
    
   NOTE there is no `-` in the package name

4. Start the Puppet Server service

        service puppetserver start
    
### Reporting Issues

Issues can be submitted at https://tickets.puppetlabs.com/browse/SERVER
   
[repodocs]: https://docs.puppetlabs.com/guides/puppetlabs_package_repositories.html
