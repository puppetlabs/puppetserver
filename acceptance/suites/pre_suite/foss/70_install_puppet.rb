step "Install MRI Puppet Agents."
  hosts.each do |host|
    puppet_version = test_config[:puppet_version]

    if puppet_version
      install_package host, 'puppet'
    else
      puppet_version = test_config[:puppet_version]

      variant, _, _, _ = host['platform'].to_array

      case variant
      when /^(debian|ubuntu)$/
        puppet_version += "-1puppetlabs1"
        install_package host, "puppet=#{puppet_version} puppet-common=#{puppet_version}"
      when /^(redhat|el|centos)$/
        install_package host, 'puppet', puppet_version
      end

    end

  end

step "Install Puppet Server."
  make_env = {
    "prefix" => "/usr",
    "confdir" => "/etc/",
    "rundir" => "/var/run/puppet-server",
    "initdir" => "/etc/init.d",
  }
  install_puppet_server master, 'puppet-server', make_env
