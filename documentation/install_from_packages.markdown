---
layout: default
title: "Puppet Server: Installing From Packages"
canonical: "/puppetserver/latest/install_from_packages.html"
---

[repodocs]: https://docs.puppetlabs.com/guides/puppetlabs_package_repositories.html
[passengerguide]: https://docs.puppetlabs.com/guides/passenger.html


## System Requirements

Puppet Server is configured to use 2GB of RAM by default. If you'd like to just play around with an installation on a Virtual Machine, this much memory is not necessary. To change the memory allocation, please see [Memory Allocation](#memory-allocation).

## Quick Start

1. [Enable the Puppet Labs package repositories][repodocs], if you haven't already done so.
2. Stop the existing Puppet master service. The method for doing this varies depending on how your system is set up.

  If you're running a WEBrick Puppet master, use: `service puppetmaster stop`.

  If you're running Puppet under Apache, you'll instead need to disable the puppetmaster vhost and restart the Apache service. The exact method for this depends on what your Puppet master vhost file is called and how you enabled it. For full documentation, see the [Passenger guide][passengerguide].

    * On a Debian system, the command might be something like `sudo a2dissite puppetmaster`.
    * On RHEL/CentOS systems, the command might be something like `sudo mv /etc/httpd/conf.d/puppetmaster.conf ~/`. Alternatively, you can delete the file instead of moving it.

  After you've disabled the vhost, restart Apache, which is a service called either `httpd` or `apache2`, depending on your OS.

  Alternatively, if you don't need to keep the Apache service running, you can stop Apache with `service httpd stop` or `service apache2 stop`.

3. Install the Puppet Server package by running:

        yum install puppetserver

   Or

        apt-get install puppetserver

   Note that there is no `-` in the package name.

4. Start the Puppet Server service:

        service puppetserver start

## Memory Allocation

By default, Puppet Server will be configured to use 2GB of RAM. However, if you want to experiment with Puppet Server on a VM, you can safely allocate as little as 512MB of memory. To change the Puppet Server memory allocation:

1. Open `/etc/sysconfig/puppetserver` and modify these settings:

        # Modify this if you'd like to change the memory allocation, enable JMX, etc
        JAVA_ARGS="-Xms2g -Xmx2g"

    Replace 2g with the amount of memory you want to allocate to Puppet Server. For example, to allocate 1GB of memory, use `JAVA_ARGS="-Xms1g -Xmx1g"`; for 512MB, use `JAVA_ARGS="-Xms512m -Xmx512m"`.

     For more information about the recommended settings for the JVM, please see [http://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm](http://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm).

2. Restart the `puppetserver` service after making any changes to this file.

## Reporting Issues

Submit issues at [https://tickets.puppetlabs.com/browse/SERVER](https://tickets.puppetlabs.com/browse/SERVER).

