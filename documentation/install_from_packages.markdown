---
layout: default
title: "Install Puppet Server"
canonical: "/puppetserver/latest/install_from_packages.html"
---

[repodocs]: https://puppet.com/docs/puppet/latest/puppet_platform.html

Puppet Server is a required application that runs on the Java Virtual Machine (JVM). It controls the configuration information for one or more managed agent nodes.

> Note: If you have any issues with the steps below, submit these to our [bug tracker](https://tickets.puppet.com/browse/SERVER).

## Before you begin

Review the supported operating systems and make sure you have a supported version of Java. 

### Supported operating systems

Puppet provides official packages that install Puppet Server 6 and all of its prerequisites on x86_64 architectures for the following platforms:

* Red Hat Enterprise Linux 6, 7
* Debian 8 (Jessie), 9 (Stretch), 10 (Buster)
* Ubuntu 16.04 (Xenial), 18.04 (Bionic)
* SLES 12 SP1

### Java support

Puppet Server versions are tested against the following versions of Java:

| Puppet Server  | Java  |
|---|---|
| 2.x  | 7, 8  |
| 5.x  | 8  |
| 6.0-6.5  | 8, 11 (experimental)  |
| 6.6 and later  | 8, 11  |

Some Java versions may work with other Puppet Server versions, but we do not test or support those cases. Community submitted patches for support greater than Java 11 are welcome. Both Java 8 and 11 are considered long-term support versions and are planned to be supported by upstream maintainers until 2022 or later.

> Note: Java 8 runtime packages do not exist in the standard repositories for Debian 8 (Jessie) or Ubuntu 18.04 (Bionic).  To install Puppet Server on Jessie, [configure the `jessie-backports` repository](https://backports.debian.org/Instructions/). To install Puppet Server on Bionic, enable the [universe repository](https://help.ubuntu.com/community/Repositories/Ubuntu).

## Install Puppet Server

Puppet Server is configured to use 2 GB of RAM by default. If you're just testing an installation on a Virtual Machine, this much memory is not necessary. To change the memory allocation, see [Running Puppet Server on a VM](#Running-Puppet-Server-on-a-VM).

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
sudo systemctl start puppetserver
``` 

4. To check you have installed the Puppet Server package correctnly, run the following command to check the version:

```
puppetserver -v
```

### What to do next

Now that Puppet Server is installed, move on to these next steps:

1. [Install a Puppet agent](https://puppet.com/docs/puppet/latest/install_agents.html)
2. [Install PuppetDB](https://puppet.com/docs/puppetdb/latest/install_via_module.html) (optional) ⁠— if you would like to to enable extra features, including enhanced queries and reports about your infrastructure.

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
