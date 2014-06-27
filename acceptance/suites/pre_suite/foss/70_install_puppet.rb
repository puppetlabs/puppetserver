step "Install MRI Puppet Agents."
  hosts.each do |host|
    puppet_version = test_config[:puppet_version]

    variant, _, _ = host['platform'].split('-', 3)
    if variant =~ /^(debian|ubuntu)$/ and puppet_version
      puppet_version += "-1puppetlabs1"
      install_package host, "puppet=#{puppet_version} puppet-common=#{puppet_version}"
    elsif variant =~ /^(redhat|el|centos)$/
      install_package host, 'puppet', puppet_version
    end

  end

step "Install JVM Puppet Master."
  make_env = {
    "prefix" => "/usr",
    "confdir" => "/etc/",
    "rundir" => "/var/run/jvm-puppet",
    "initdir" => "/etc/init.d",
  }
  install_jvm_puppet master, 'jvm-puppet', make_env
