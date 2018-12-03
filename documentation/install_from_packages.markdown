---
layout: default
title: "Puppet Server: Installing From Packages"
canonical: "/puppetserver/latest/install_from_packages.html"
---

[repodocs]: https://puppet.com/docs/puppet/latest/puppet_platform.html

## System Requirements

Puppet Server is configured to use 2 GB of RAM by default. If you'd like to just play around with an installation on a Virtual Machine, this much memory is not necessary. To change the memory allocation, see [Memory Allocation](#memory-allocation).

> If you're also using PuppetDB, check its [requirements](https://puppet.com/docs/puppetdb/latest/index.html#system-requirements).

## Platforms with Packages

Puppet provides official packages that install Puppet Server 6.0 and all of its prerequisites on x86_64 architectures for the following platforms, as part of [Puppet Platform][repodocs].

### Red Hat Enterprise Linux

-   Enterprise Linux 6
-   Enterprise Linux 7

### CentOS

-   CentOS 6
-   CentOS 7

### Debian

-   Debian 8 (Jessie)
-   Debian 9 (Stretch)

Java 8 runtime packages do not exist in the standard repositories for Debian 8 (Jessie).  To install Puppet Server on Jessie, [configure the `jessie-backports` repository](https://backports.debian.org/Instructions/), which includes openjdk-8:

```
echo "deb http://ftp.debian.org/debian jessie-backports main" > /etc/apt/sources.list.d/jessie-backports.list
apt-get update
apt-get -t jessie-backports install "openjdk-8-jdk-headless"
apt-get install puppetserver
```

If you upgraded on Debian from older versions of Puppet Server, or from Java 7 to Java 8, you must also configure your server to use Java 8 by default for Puppet Server 5.x:

```
update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java
```

### Ubuntu

-   Ubuntu 18.04 (Bionic)
-   Ubuntu 16.04 (Xenial)

On Ubuntu 18.04, enable the [universe repository](https://help.ubuntu.com/community/Repositories/Ubuntu), which contains packages necessary for Puppet Server.

### SUSE Linux Enterprise Server

-   SLES 12 SP1

## Quick Start

1.  [Enable the Puppet package repositories][repodocs], if you haven't already done so.
2.  Stop any existing `puppetmaster` or `puppetserver` service.

        service <service_name> stop

    Or

        systemctl stop <service_name>

3.  Install the Puppet Server package by running:

        yum install puppetserver

    Or

        apt-get install puppetserver

    Note that there is no `-` in the package name.

4. Generate a root and intermediate signing CA for Puppet Server

        puppetserver ca setup

   (This step is not needed if upgrading.)

5.  Start the Puppet Server service:

        systemctl start puppetserver

    Or

        service puppetserver start

## Platforms without Packages

For platforms and architectures where no official packages are available, you can build Puppet Server from source. Such platforms are not tested, and running Puppet Server from source is not recommended for production use.

For details, see [Running from Source](./dev_running_from_source.markdown).

## Memory Allocation

By default, Puppet Server is configured to use 2GB of RAM. However, if you want to experiment with Puppet Server on a VM, you can safely allocate as little as 512MB of memory. To change the Puppet Server memory allocation, you can edit the init config file.

### Location

* For RHEL or CentOS, open `/etc/sysconfig/puppetserver`
* For Debian or Ubuntu, open `/etc/default/puppetserver`

### Settings

1. Update the line:

        # Modify this if you'd like to change the memory allocation, enable JMX, etc
        JAVA_ARGS="-Xms2g -Xmx2g"

    Replace 2g with the amount of memory you want to allocate to Puppet Server. For example, to allocate 1GB of memory, use `JAVA_ARGS="-Xms1g -Xmx1g"`; for 512MB, use `JAVA_ARGS="-Xms512m -Xmx512m"`.

    For more information about the recommended settings for the JVM, see [Oracle's docs on JVM tuning.](http://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm)

2. Restart the `puppetserver` service after making any changes to this file.

## Reporting Issues

Submit issues to our [bug tracker](https://tickets.puppet.com/browse/SERVER).
