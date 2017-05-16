
if test_config[:puppetserver_install_type] == :package
  package_build_version = ENV['PACKAGE_BUILD_VERSION']
  if package_build_version.nil?
    abort("Environment variable PACKAGE_BUILD_VERSION required for package installs!")
  end

  step "Setup Puppet Server dev repository on the master." do
    install_opts = options.merge({ :dev_builds_repos => ["PC1"]})
    install_puppetlabs_dev_repo(master, 'puppetserver', package_build_version, nil, install_opts)
  end

  step "Setup Puppet dev repository on the master." do
    install_puppet_agent_dev_repo_on(master, {:puppet_agent_version => test_config[:puppet_build_version]})
  end
end
