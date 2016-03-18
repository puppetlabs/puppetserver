---
layout: default
title: "Puppet Server: Installing From Packages"
canonical: "/puppetserver/latest/install_from_packages.html"
---

[repodocs]: /puppet/latest/reference/puppet_collections.html
[passengerguide]: /guides/passenger.html

## System Requirements

Puppet Server is configured to use 2 GB of RAM by default. If you'd like to just play around with an installation on a Virtual Machine, this much memory is not necessary. To change the memory allocation, see [Memory Allocation](#memory-allocation).

> **Note:** Puppet masters running Puppet Server 2.3 depend on [Puppet Agent 1.4.0](/puppet/4.4/reference/about_agent.html) or newer, which installs [Puppet 4.4](/puppet/4.4/) and compatible versions of its related tools and dependencies on the server. Puppet agents running older versions of Puppet Agent can connect to Puppet Server 2.3 --- this requirement applies to the Puppet Agent running on the Puppet Server node *only*.
>
> If you're also using PuppetDB, also check its [requirements](/puppetdb/latest/#system-requirements).

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

        systemctl start puppetserver

    Or

        service puppetserver start

## Memory Allocation

By default, Puppet Server will be configured to use 2GB of RAM. However, if you want to experiment with Puppet Server on a VM, you can safely allocate as little as 512MB of memory. To change the Puppet Server memory allocation, you can edit the init config file.

### Location

* `/etc/sysconfig/puppetserver` --- RHEL
* `/etc/default/puppetserver` --- Debian

1. Open the init config file:

        # Modify this if you'd like to change the memory allocation, enable JMX, etc
        JAVA_ARGS="-Xms2g -Xmx2g"

    Replace 2g with the amount of memory you want to allocate to Puppet Server. For example, to allocate 1GB of memory, use `JAVA_ARGS="-Xms1g -Xmx1g"`; for 512MB, use `JAVA_ARGS="-Xms512m -Xmx512m"`.

    For more information about the recommended settings for the JVM, see [Oracle's docs on JVM tuning.](http://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm)

2. Restart the `puppetserver` service after making any changes to this file.

## Reporting Issues

Submit issues to our [bug tracker](https://tickets.puppetlabs.com/browse/SERVER).
