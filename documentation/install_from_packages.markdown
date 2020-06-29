---
layout: default
title: "Install Puppet Server"
canonical: "/puppetserver/latest/install_from_packages.html"
---

[repodocs]: https://puppet.com/docs/puppet/latest/puppet_platform.html

Puppet provides official packages that install Puppet Server 6.0 and all of its prerequisites on x86_64 architectures for the following platforms, as part of [Puppet Platform][repodocs].

* Red Hat Enterprise Linux 6, 7
* Debian 8 (Jessie), 9 (Stretch), 10 (Buster)
* Ubuntu 18.04 (Bionic) - enable the [universe repository](https://help.ubuntu.com/community/Repositories/Ubuntu), which contains packages necessary for Puppet Server, 16.04 (Xenial)
* SLES 12 SP1

> Note: Java 8 runtime packages do not exist in the standard repositories for Debian 8 (Jessie).  To install Puppet Server on Jessie, [configure the `jessie-backports` repository](https://backports.debian.org/Instructions/). 

### Before you begin

Puppet Server is configured to use 2 GB of RAM by default. If you're just testing an installation on a Virtual Machine, this much memory is not necessary. To change the memory allocation, see [Memory Allocation](#memory-allocation).

#### Java support

Puppet Server versions are tested against the following versions of Java:

| Puppet Server  | Java  |
|---|---|
| 2.x  | 7, 8  |
| 5.x  | 8  |
| 6.0-6.5  | 8, 11 (experimental)  |
| 6.6 and later  | 8, 11  |


Some Java versions may work with other Puppet Server versions, but we do not test or support those cases. Community submitted patches for support greater than Java 11 are welcome. Both Java 8 and 11 are considered long-term support versions and are planned to be supported by upstream maintainers until 2022 or later.

## Install the Puppet Server package

1.  [Enable the Puppet package repositories][repodocs], if you haven't already done so.

2.  Install the Puppet Server package by running one of the following commands.

Red Hat operating systems: 

````
yum install puppetserver
````

Debian and Ubuntu: 

```
apt-get install puppetserver
```

There is no `-` in the package name.

> Note: If you're upgrading, stop any existing `puppetmaster` or `puppetserver` service by running `service <service_name> stop` or `systemctl stop <service_name>`.

3.  Start the Puppet Server service:

```
systemctl start puppetserver
``` 

or 

```
service puppetserver start
```

## Platforms with packages

Puppet provides official packages that install Puppet Server 6.0 and all of its prerequisites on x86_64 architectures for the following platforms, as part of [Puppet Platform][repodocs].

* Red Hat Enterprise Linux 6
* Red Hat Enterprise Linux 7
* Debian 8 (Jessie)
* Debian 9 (Stretch)
* Debian 10 (Buster)
* Ubuntu 18.04 (Bionic) - enable the [universe repository](https://help.ubuntu.com/community/Repositories/Ubuntu), which contains packages necessary for Puppet Server.
* Ubuntu 16.04 (Xenial)
* SLES 12 SP1

> Note: Java 8 runtime packages do not exist in the standard repositories for Debian 8 (Jessie).  To install Puppet Server on Jessie, [configure the `jessie-backports` repository](https://backports.debian.org/Instructions/). 


## Running Puppet Server on a VM

By default, Puppet Server is configured to use 2GB of RAM. However, if you want to experiment with Puppet Server on a VM, you can safely allocate as little as 512MB of memory. To change the Puppet Server memory allocation, you can edit the init config file.

* For RHEL or CentOS, open `/etc/sysconfig/puppetserver`
* For Debian or Ubuntu, open `/etc/default/puppetserver`

1. In your settings, update the line:

        # Modify this if you'd like to change the memory allocation, enable JMX, etc
        JAVA_ARGS="-Xms2g -Xmx2g"

    Replace 2g with the amount of memory you want to allocate to Puppet Server. For example, to allocate 1GB of memory, use `JAVA_ARGS="-Xms1g -Xmx1g"`; for 512MB, use `JAVA_ARGS="-Xms512m -Xmx512m"`.

    For more information about the recommended settings for the JVM, see [Oracle's docs on JVM tuning.](http://docs.oracle.com/cd/E15523_01/web.1111/e13814/jvm_tuning.htm)

2. Restart the `puppetserver` service after making any changes to this file.
