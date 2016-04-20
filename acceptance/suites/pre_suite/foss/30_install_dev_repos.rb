install_opts = options.merge( { :dev_builds_repos => ["PC1"] })
repo_config_dir = 'tmp/repo_configs'

case test_config[:puppetserver_install_type]
when :package
  step "Setup Puppet Server repositories." do
    package_build_version = ENV['PACKAGE_BUILD_VERSION']
    if package_build_version
      install_puppetlabs_dev_repo master, 'puppetserver', package_build_version,
                                  repo_config_dir, install_opts
    else
      abort("Environment variable PACKAGE_BUILD_VERSION required for package installs!")
    end
  end
end

puppet_build_version = test_config[:puppet_build_version]
if puppet_build_version
  confine_block :except, :platform => ['windows'] do
    step "Setup Puppet Labs Dev Repositories." do
      hosts.each do |host|
        install_puppetlabs_dev_repo host, 'puppet-agent', puppet_build_version,
                                    repo_config_dir, install_opts
      end
    end
  end
end
