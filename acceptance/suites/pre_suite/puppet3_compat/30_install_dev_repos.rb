case test_config[:puppetserver_install_type]
when :package
  step "Setup Puppet Server dev repository on the master." do
    package_build_version = ENV['PACKAGE_BUILD_VERSION']
    if package_build_version
      install_puppetlabs_dev_repo master, 'puppetserver', package_build_version
    else
      abort("Environment variable PACKAGE_BUILD_VERSION required for package installs!")
    end
  end

  puppet_build_version = test_config[:puppet_build_version]
  if puppet_build_version
    confine_block :except, :platform => ['windows'] do
      step "Setup Puppet dev repository on the master." do
        install_puppetlabs_dev_repo master, 'puppet-agent', puppet_build_version
      end
    end
  end
end

