case test_config[:puppetserver_install_type]
when :package
  step "Setup Puppet Server repositories." do
    package_build_version = ENV['PACKAGE_BUILD_VERSION']
    if package_build_version
      install_puppetlabs_dev_repo master, 'puppet-server', package_build_version
    else
      abort("Environment variable PACKAGE_BUILD_VERSION required for package installs!")
    end
  end
end

puppet_build_version = test_config[:puppet_build_version]
if puppet_build_version
  step "Setup Puppet Labs Dev Repositories." do
    hosts.each do |host|
      install_puppetlabs_dev_repo host, 'puppet', puppet_build_version
    end
  end
end

# If we are using dev repository to install puppet then there is no point to
# install release repository.
step "Setup Puppet Labs Release repositories." do
  hosts.each do |host|
    install_puppetlabs_release_repo host
  end
end
