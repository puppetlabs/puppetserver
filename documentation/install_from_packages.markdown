---
layout: default
title: "Puppet Server: Installing From Packages"
canonical: "/puppetserver/latest/install_from_packages.html"
---

[repodocs]: https://puppet.com/docs/puppet/latest/puppet_platform.html
[passengerguide]: https://puppet.com/docs/puppet/latest/passenger.html

Puppet Server is configured to use 2 GB of RAM by default. If you'd like to just play around with an installation on a Virtual Machine, this much memory is not necessary. To change the memory allocation, see [Memory Allocation](#memory-allocation).

> If you're also using PuppetDB, check its [requirements](https://puppet.com/docs/puppetdb/latest/index.html#system-requirements).

## Install Puppet Server from packages

1.  [Enable the Puppet package repositories][repodocs], if you haven't already done so.

2.  Install the Puppet Server package by running:

        yum install puppetserver

    or

        apt-get install puppetserver

    Note that there is no `-` in the package name.

3.  Start the Puppet Server service:

`systemctl start puppetserver` or `service puppetserver start`
        
> Note: If you're uprgading, stop any existing `puppetmaster` or `puppetserver` service by running `service <service_name> stop` or `systemctl stop <service_name>`.

## Platforms with packages

Puppet provides official packages that install Puppet Server 5.1 and all of its prerequisites on x86_64 architectures for the following platforms, as part of [Puppet Platform][repodocs].

* Red Hat Enterprise Linux 6
* Red Hat Enterprise Linux 7
* Debian 8 (Jessie)
* Debian 10 (Stretch)
* Ubuntu 18.04 (Bionic) - enable the [universe repository](https://help.ubuntu.com/community/Repositories/Ubuntu), which contains packages necessary for Puppet Server.
* Ubuntu 16.04 (Xenial)
* SLES 12 SP1

> Note: Java 8 runtime packages do not exist in the standard repositories for Debian 8 (Jessie).  To install Puppet Server on Jessie, [configure the `jessie-backports` repository](https://backports.debian.org/Instructions/). 

## Platforms without packages

For platforms and architectures where no official packages are available, you can build Puppet Server from source. Such platforms are not tested, and running Puppet Server from source is not recommended for production use.

For details, see [Running from Source](./dev_running_from_source.markdown).

## Memory allocation

By default, Puppet Server is configured to use 2GB of RAM. However, if you want to experiment with Puppet Server on a VM, you can safely allocate as little as 512MB of memory. To change the Puppet Server memory allocation, you can edit the init config file.

* For RHEL or CentOS, open `/etc/sysconfig/puppetserver`
* For Debian or Ubuntu, open `/etc/default/puppetserver`

1. In your settings, update the line:

        # Modify this if you'd like to change the memory allocation, enable JMX, etc
        JAVA_ARGS="-Xms2g -Xmx2g"

    Replace 2g with the amount of memory you want to allocate to Puppet Server. For example, to allocate 1GB of memory, use `JAVA_ARGS="-Xms1g -Xmx1g"`; for 512MB, use `JAVA_ARGS="-Xms512m -Xmx512m"`.

    For more information about the recommended settings for the JVM, see [Oracle's docs on JVM tuning.](http://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm)

2. Restart the `puppetserver` service after making any changes to this file.

## Reporting Issues

Submit issues to our [bug tracker](https://tickets.puppet.com/browse/SERVER).
