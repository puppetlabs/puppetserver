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

step "Run puppet as puppet user to prevent permissions errors later."
  puppet_apply_as_puppet_user

step "Upgrade nss to version that is hopefully compatible with jdk version puppetserver will use." do
  nss_package=nil
  variant, _, _, _ = master['platform'].to_array
  case variant
  when /^(debian|ubuntu)$/
    nss_package_name="libnss3"
  when /^(redhat|el|centos)$/
    nss_package_name="nss"
  end
  if nss_package_name
    master.upgrade_package(nss_package_name)
  else
    logger.warn("Don't know what nss package to use for #{variant} so not installing one")
  end
end

if (test_config[:puppetserver_install_mode] == :upgrade)
  step "Upgrade Puppet Server."
    upgrade_package(master, "puppetserver")
else
  step "Install Puppet Server."
    make_env = {
      "prefix" => "/usr",
      "confdir" => "/etc/",
      "rundir" => "/var/run/puppetserver",
      "initdir" => "/etc/init.d",
    }
    install_puppet_server master, make_env
end
