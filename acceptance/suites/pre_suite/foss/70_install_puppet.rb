step "Install MRI Puppet Agents." do
  sha = test_config[:puppet_build_version]
  install_from_build_data_url('puppet-agent', "http://builds.delivery.puppetlabs.net/puppet-agent/#{sha}/artifacts/#{sha}.yaml", hosts)
end

step "Verify agent installs" do
  agents.each do |agent|
    on agent, puppet('--version')
    ruby = ruby_command(agent)
    on agent, "#{ruby} --version"
  end
end

step "Upgrade nss to version that is hopefully compatible with jdk version puppetserver will use." do
  nss_package_name=nil
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
