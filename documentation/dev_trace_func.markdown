---
layout: default
title: "Puppet Server: Tracing Code Events"
canonical: "/puppetserver/latest/dev_trace_func.html"
---


The JRuby runtime supports the Ruby [set_trace_func]
(http://ruby-doc.org/core-1.9.3/Kernel.html#method-i-set_trace_func) Kernel
method for tracing code events, e.g., lines of code being executed and calls
to C-language routines or Ruby methods.  This can likewise be used in Puppet
Server for tracing.

In order to enable a more verbose level of tracing, e.g., to capture lower-level
calls into C code, the `jruby.debug.fullTrace` Java property must be set to
"true".  If you are running Puppet Server from source, this can be done by
adding the option to the `project.clj` file:

~~~ini
:jvm-opts ["-XX:MaxPermSize=256m" "-Djruby.debug.fullTrace=true"]
~~~

If you are running Puppet Server from a package, this can be done by adding the
option to the `puppetserver` file in `/etc/sysconfig` or `/etc/default`,
depending upon your OS distribution:

~~~ini
JAVA_ARGS="-Xms2g -Xmx2g -XX:MaxPermSize=256m -Djruby.debug.fullTrace=true"
~~~

A call to the `set_trace_func` function can be done in one of the Ruby files in
the Puppet Server code.  For the trace to be in effect for the full execution
of Ruby code, one common place to put this call would be at the top of the
`../src/ruby/puppet-server-lib/puppet/server/master.rb` file, the Puppet Server
master class.  A basic implementation might look like this:

~~~ruby
set_trace_func proc { |event, file, line, id, binding, classname|
  printf "%8s %s:%-2d %10s %8s\n", event, file, line, id, classname
}
~~~

Note that `printf` will write each trace line to stdout.  If you are running
Puppet Server from a package install, stdout should be routed to the
`/var/log/puppetserver-daemon.log` file.

Lines of output from `set_trace_func` look like the following:

~~~
 c-call /usr/share/puppetserver/puppet-server-release.jar!/META-INF/jruby.home/lib/ruby/shared/jopenssl19/openssl/ssl-internal.rb:30 initialize OpenSSL::X509::Store
~~~

You could use this technique to locate any references made to specific class
names from code and the active stack at the point of each reference.  For
example, to locate callers of any `OpenSSL` classes, you could add the following
to the `set_trace_func` call:

~~~ruby
set_trace_func proc { |event, file, line, id, binding, classname|
  if classname.to_s =~ /OpenSSL/
     printf "%8s %s:%-2d %10s %8s\n", event, file, line, id, classname
     puts caller
  end
}
~~~
