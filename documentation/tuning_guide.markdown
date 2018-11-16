---
layout: default
title: "Puppet Server: Tuning Guide"
canonical: "/puppetserver/latest/tuning_guide.html"
---

Puppet Server provides many configuration options that can be used to tune the
server for maximum performance and hardware resource utilization. In this guide,
we'll highlight some of the most important settings that you can use to get
the best performance in your environment.

## Puppet Server and JRuby

Before you begin tuning your configuration, it's helpful to have a little bit
of context on how Puppet Server uses JRuby to handle incoming HTTP requests from
your Puppet agents.

When Puppet Server starts up, it creates a pool of JRuby interpreters to use
as workers when it needs need to execute some of the Puppet Ruby code. You can think
of these almost as individual Ruby "virtual machines" that are controlled by
Puppet Server; it's not entirely dissimilar to the way that Passenger spawns
several Ruby processes to hand off work to.

Puppet Server isolates these JRuby instances so that they will only be allowed
to handle one request at a time. This ensures that we don't encounter any
concurrency issues, since the Ruby code is not thread-safe. When an HTTP request
comes in to Puppet Server, and it determines that some Ruby code will need to be
executed in order to handle the request, Puppet Server "borrows" a JRuby instance
from the pool, uses it to do the work, and then "returns" it to the pool.  If
there are no JRuby instances available in the pool at the time a request
comes in (presumably because all of the JRuby instances are already in use handling
other requests), Puppet Server will block the request until one becomes available.

(In the future, this approach will allow us to do some really powerful things
such as creating multiple pools of JRubies and isolating each of your Puppet
environments to a single pool, to ensure that there is no pollution from one
Puppet environment to the next.)

This brings us to the two most important settings that you can use to tune your
Puppet Server.

### Number of JRubies

The most important setting that you can use to improve the throughput of your
Puppet Server installation is the [`max-active-instances`](./configuration.markdown#puppetserver_conf)
setting.  The value of this setting is used by Puppet Server to determine how
many JRuby instances to create when the server starts up.

From a practical perspective, this setting basically controls how many Puppet
agent runs Puppet Server can handle concurrently. The minimum value you can
get away with here is `1`, and if your installation is small enough that
you're unlikely to ever have more than one Puppet agent checking in with the
server at exactly the same time, this is totally sufficient.

However, if you specify a value of `1` for this setting, and then you have two
Puppet agent runs hitting the server at the same time, the requests being made by the second agent will be effectively blocked until the server has finished handling all of the requests from the first agent. In other words, one of Puppet Server's threads will have "borrowed" the single JRuby instance from the pool to handle the requests from the first agent, and only when those requests are completed will it return the JRuby instance
to the pool. At that point, the next thread can "borrow" the JRuby instance to
use to handle the requests from the second agent.

Assuming you have more than one CPU core in your machine, this situation means
that you won't be getting the maximum possible throughput from your Puppet Server
installation. Increasing the value from `1` to `2` would mean that Puppet Server
could now use a second CPU core to handle the requests from a second Puppet agent
simultaneously.

It follows, then, that the maximum sensible value to use for this setting will
be roughly the number of CPU cores you have in your server. Setting the value
to something much higher than that won't improve performance, because even if there
are extra JRuby instances available in the pool to do work, they won't be able
to actually do any work if all of the CPU cores are already busy using JRuby
instances to handle incoming agent requests.

(There are some exceptions to this rule. For example, if you have report processors that make a network connection as part of the processing of a report, and if there is a chance
that the network operation is slow and will block on I/O for some period of time,
then it might make sense to have more JRuby instances than the number of cores. The JVM is smart enough to suspend the thread that is handling those kinds of requests and use the CPUs for other work, assuming there are still JRuby instances available in the pool. In a case like this you might want to set `max-active-instances` to a value higher than the number of CPUs.)

At this point you may be wondering, "What's the downside to just setting
`max-active-instances` to a really high value?" The answer to this question, in
a nutshell, is "memory usage". This brings us to the other extremely important setting to consider for Puppet Server.

### JVM Heap Size

The JVM's "max heap size" controls the maximum amount of (heap*[[1]](#footnotes)
memory that the JVM process is allowed to request from the operating system. You
can set this value via the `-Xmx` command-line argument at JVM startup. (In the
case of Puppet Server, you'll find this setting in the "defaults" file for Puppet
Server for your operating system; this will generally be something like
`/etc/sysconfig/puppetserver` or `/etc/defaults/puppetserver`.)

> **Upgrade note:** If you modified the defaults file in Puppet Server 2.4.x or earlier, then lost those modifications or see `Service ':PoolManagerService' not found` warnings after upgrading to Puppet Server 2.5, be aware that the package might have attempted to overwrite the file during the upgrade. See the [Puppet Server 2.5 release notes](https://docs.puppet.com/puppetserver/2.5/release_notes.html) for details.

If your application's memory usage approaches this value, the JVM will try to
get more aggressive with garbage collection to free up memory. In certain
situations, you may see increased CPU activity related to this garbage collection. If the JVM is unable to recover enough memory to keep the application running
smoothly, you will eventually encounter an `OutOfMemoryError`, and the process
will shut down.

For Puppet Server, we also use a JVM argument,
`-XX:HeapDumpOnOutOfMemoryError`, to cause the JVM to dump an `.hprof` file to
disk. This is basically a memory snapshot at the point in time where the
error occurred; it can be loaded into various profiling tools to get a better
understanding of where the memory was being used.

(Note that there is another setting, "min heap size", that is controlled via
the -Xms setting; [Oracle recommends](http://www.oracle.com/technetwork/java/gc-tuning-5-138395.html#0.0.0.%20Total%20Heap|outline) setting this value to the same value that you use for -Xmx.)

The most important factor when determining the max heap size for Puppet Server
is the value of `max-active-instances`. Each JRuby instance needs to load up
a copy of the Puppet Ruby code, and then needs some amount of memory overhead
for all of the garbage that gets generated during a Puppet catalog compilation.
Also, the memory requirements will vary based on how many Puppet modules you
have in your module path, how much Hiera data you have, etc. At this time we
estimate that a reasonable ballpark figure is about 512MB of RAM per JRuby
instance, but that can vary depending on some characteristics of your Puppet
codebase. For example, if you have a really high number of modules or a great
deal of Hiera data, you might find that you need more than 512MB per JRuby
instance.

You'll also want to allocate a little extra heap to be used by the rest of the
things going on in Puppet Server: the web server, etc. So, a good rule of thumb
might be 512MB + (max-active-instances * 512MB).

We're working on some optimizations for really small installations (for testing,
demos, etc.). Puppet Server should run fine with a value of 1 for
`max-active-instances` and a heap size of 512MB, and we might be able to improve
that further in the future.

### Tying Together `max-active-instances` and Heap Size

We're still gathering data on what the best default settings are, to try to provide
an out-of-the-box configuration that works well in most environments. In versions
prior to 1.0.8 in the 1.x series (compatible with Puppet 3.x), and prior to 2.1.0
in the 2.x series (compatible with Puppet 4.x), the default
value is `num-cpus + 2`.  This value will be far too high if you're running on
a system with a large number of CPU cores.

As of Puppet Server 1.0.8 and 2.1.0, if you don't provide an explicit value for this setting,
we'll default to `num-cpus - 1`, with a minimum value of `1` and a maximum value of
`4`. The maximum value of `4` is probably too low for production environments
with beefy hardware and a high number of Puppet agents checking in, but our
current thinking is that it's better to ship with a default setting that is too
low and allow you to tune up, than to ship with a default setting that is too
high and causes you to run into `OutOfMemory` errors. In general,
it's recommended that you explicitly set this value to something that you think
is reasonable in your environment. To encourage this, we log a warning
message at startup if you haven't provided an explicit value.

### Potential JAVA ARGS settings

If you’re working outside of lab environment, increase `ReservedCodeCache` to `512m` under normal load. If you’re working with 6-12 JRuby instances (or a `max-requests-per-instance` value significantly less than 100k), run with a `ReservedCodeCache` of 1G. Twelve or more JRuby instances in a single server might require 2G or more.

Similar caveats regarding scaling `ReservedCodeCache` might apply if users are managing `MaxMetaspace`.

## Footnotes

[1] The vast majority of the memory footprint of a JVM process can usually be
    accounted for by the heap size. However, there is some amount of non-heap
    memory that will always be used, and for programs that call out to native
    code at all, there may be a bit more. Generally speaking, the resident
    memory usage of a JVM process shouldn't exceed the max heap size by more
    than 256MB or so, but exceeding the max heap size by some amount is normal.
