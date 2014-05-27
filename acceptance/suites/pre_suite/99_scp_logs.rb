step "Preserve JVM Puppet Log Files"
  scp_from master, "/var/log/jvm-puppet/jvm-puppet.log", "./"
  scp_from master, "/var/log/jvm-puppet/jvm-puppet-daemon.log", "./"
