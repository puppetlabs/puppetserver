test_name "Upgrade Puppetserver"

install_opts = options.merge({ :dev_builds_repos => ["PC1"]})

package_build_version = ENV['PACKAGE_BUILD_VERSION']
if package_build_version.nil?
  abort("Environment variable PACKAGE_BUILD_VERSION required for package installs!")
end

step "Setup Puppet Server dev repository on the master." do
  install_puppetlabs_dev_repo(master, 'puppetserver', package_build_version, nil, install_opts)
end

step "Setup Puppet dev repository on the master." do
  install_puppetlabs_dev_repo(master, 'puppet-agent', test_config[:puppet_build_version], nil, install_opts)
end

step "Upgrade Puppet Server." do
  install_puppet_server master
  on(master, puppet("resource service puppetserver ensure=stopped"))
  on(master, puppet("resource service puppetserver ensure=running"))
end

step "Verify that agents can connect to the server" do
  on hosts, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0]
end
