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
