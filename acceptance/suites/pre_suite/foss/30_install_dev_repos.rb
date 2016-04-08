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

if puppetdb_supported_platforms.include?(master.platform) then
  step "Install PuppetDB repository" do
    install_puppetlabs_dev_repo(
      master, 'puppetdb', test_config[:puppetdb_build_version],
      repo_config_dir, install_opts)

    # Internal packages on ubuntu/debian aren't authenticated and thus apt
    # will fail to install PuppetDB on those platforms.
    # This hack tells apt that we can trust the PuppetDB packages until RE-6014
    # is resolved, at which point this [trusted=yes] will already be in the file
    # and we can delete the on(master, "sed ...") block below.
    # This should make the puppetlabs-puppetdb module happily install PuppetDB on
    # ubuntu/debian.
    on(master, <<TRUSTPACKAGES)
if [ -e /etc/apt/sources.list.d/pl-puppetdb* ]; then
  sed -i -e 's/deb/deb [trusted=yes]/1' /etc/apt/sources.list.d/pl-puppetdb*
fi
TRUSTPACKAGES
  end
end
